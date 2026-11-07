package com.fivegoodmins.fivegoodmins.shared.ratelimit;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.shared.AuthPaths;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing MockMvc cannot answer: whether a {@code 429} and its
 * {@code Retry-After} survive a real Tomcat.
 *
 * <p>{@link RateLimitFilter} refuses by calling {@code sendError}, so that the
 * refusal goes through the error dispatch and inherits page 19 of the frontend
 * brief on the day it is built, rather than being a response written in Java
 * with a user-facing string in it (ADR-008). The open question that buys is
 * whether a header set before {@code sendError} is still on the response the
 * client reads, or whether the error dispatch resets it. MockMvc's
 * {@code MockHttpServletResponse} keeps it either way, which is exactly why it
 * cannot be the measurement.
 *
 * <p>No CSRF token is sent, deliberately: the filter sits in front of
 * {@code CsrfFilter}, so the first three requests are refused by CSRF with a
 * {@code 403} and still spend their allowance. That makes this test a
 * round-trip check of the filter's position as well as of the header.
 *
 * <p>The JDK's own client rather than a Spring one, for two properties worth
 * having here: it does not follow redirects unless asked, and it does not
 * throw on a 4xx, so the status under test arrives as a value.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.rate-limit.refuse-requests=true",
                "app.rate-limit.login-attempts=3",
                // The second request-refusing bucket, small for the same
                // reason. The 429 *page* is reached through this one rather
                // than through login, so that the two tests below cannot
                // spend each other's allowance -- see
                // rendersTheRefusalAsAPageInTheSiteLayout.
                "app.rate-limit.registrations-per-address=3",
                // A plain HTTP origin: server.servlet.session.cookie.secure is
                // true in application.yaml and the real container honours it,
                // which is irrelevant to a request that never gets a session
                // but would be a confusing failure if it were not stated.
                "server.servlet.session.cookie.secure=false"})
@Import(TestcontainersConfiguration.class)
class RateLimitHttpTest {

    @LocalServerPort
    int port;

    @Test
    void refusesWithA429ThatCarriesRetryAfter() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(login(client).statusCode())
                    .as("attempt %d, refused by CSRF but counted", attempt)
                    .isEqualTo(403);
        }

        HttpResponse<String> refused = login(client);

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.headers().firstValue(HttpHeaders.RETRY_AFTER))
                .as("set before sendError, and still here after the error dispatch")
                .isPresent()
                .get()
                .satisfies(value -> assertThat(Integer.parseInt(value)).isPositive());
    }

    /**
     * Page 19 of the frontend brief, for the one status that cannot be reached
     * by following a link: {@code error/429.html} in the site layout.
     *
     * <p>It lives here rather than in {@code ErrorPagesTest} for the reason
     * that file's own javadoc gives for existing -- a real server, because
     * MockMvc skips the ERROR dispatch -- plus one this class already paid
     * for. A 429 needs {@code app.rate-limit.refuse-requests=true}, which the
     * suite switches off in {@code src/test/resources/application.properties};
     * switching it back on in ErrorPagesTest would fork a second Spring
     * context whose setup is about the limiter rather than about error pages.
     * This context already refuses requests.
     *
     * <p><strong>Through {@code /register}, and that is the whole reason this
     * test is not flaky.</strong> A bucket is process-wide state keyed on the
     * client address, and both tests in this class run against one limiter
     * bean from one {@code 127.0.0.1} over a window far longer than the build
     * -- so posting to {@code /login} here spent the allowance the test above
     * measures, and whichever ran second failed on its first assertion. That
     * was measured, not foreseen. Registration is the other request-refusing
     * bucket, so the two tests now share a context and nothing else.
     */
    @Test
    void rendersTheRefusalAsAPageInTheSiteLayout() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(registerAsBrowser(client).statusCode())
                    .as("attempt %d, refused by CSRF but counted", attempt)
                    .isEqualTo(403);
        }

        HttpResponse<String> refused = registerAsBrowser(client);

        assertThat(refused.statusCode()).isEqualTo(429);
        // The page's own text, from the bundle...
        assertThat(refused.body()).contains("Твърде много опити");
        // ...inside the layout, which is the half that distinguishes this from
        // the whitelabel page this refusal rendered until the template existed.
        assertThat(refused.body()).contains("5 Good Minutes", "id=\"main\"");
        // Nothing here asserts that the copy names no interval, though it
        // does and ADR-002 says why. The first version asserted it as
        // doesNotContain("минут"), which is a trap in a product called
        // "5 добри минути": the day the layout carries the brand in
        // Bulgarian, that line fails and names the wrong cause.
    }

    private HttpResponse<String> login(HttpClient client) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + AuthPaths.LOGIN))
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "username=ivan%40example.invalid&password=wrong"))
                .timeout(Duration.ofSeconds(10))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A registration POST with the two headers a browser sends and
     * {@code java.net.http} does not. {@code Accept: text/html} is the one
     * that selects the page: {@code BasicErrorController} maps the same path
     * twice, an HTML method that renders {@code error/<status>.html} and a
     * JSON method for API clients, and with the JDK client's default wildcard
     * Accept this would assert against the JSON body -- reporting a missing
     * template as a missing translation. {@code Accept-Language: bg} because the
     * assertions are on Bulgarian text and the JVM default locale decides
     * otherwise.
     */
    private HttpResponse<String> registerAsBrowser(HttpClient client) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + AuthPaths.REGISTER))
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "bg")
                // Shaped like the registration form, though nothing reads it:
                // the limiter parses no parameter and CsrfFilter refuses the
                // request before any binding happens.
                .POST(HttpRequest.BodyPublishers.ofString(
                        "email=ivan%40example.invalid&displayName=Ivan"))
                .timeout(Duration.ofSeconds(10))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
