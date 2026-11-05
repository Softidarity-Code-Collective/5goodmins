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
}
