package com.fivegoodmins.fivegoodmins.shared;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.action.ActionPaths;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.CookieManager;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Page 19 of the frontend brief: 404 and 403 are real pages in the site
 * layout rather than Boot's whitelabel page, and so is the fallback every
 * other status resolves to. (500 has no test here -- making a real server
 * throw would mean an endpoint that exists only to throw.)
 *
 * <p><strong>A real server, because MockMvc cannot see this.</strong> An
 * error page is rendered on the ERROR dispatch, and MockMvc does not perform
 * one: the status comes back correctly and the body comes back empty, so a
 * MockMvc test of an error page passes whether the template exists, is
 * missing, or throws halfway through rendering. That was measured here before
 * this file was written as it is.
 *
 * <p><strong>429 is tested elsewhere, and not by oversight.</strong>
 * {@code error/429.html} is the fourth page and the only one not reachable by
 * following a link: it needs ADR-002's limiter switched on, which the suite
 * switches off in {@code src/test/resources/application.properties}, so
 * turning it back on here would fork a Spring context whose setup is about
 * the limiter. {@code RateLimitHttpTest} already runs that context and
 * already posts until it is refused, so the page is asserted there.
 *
 * <p>The paths are under {@code /dev/**} for one reason: it is permitted in
 * {@code SecurityConfig}, and everything else currently falls through to
 * {@code anyRequest().authenticated()}, where an unknown path is a redirect
 * to the login form rather than a 404. That is also the honest state of the
 * site -- a dead link from a digest email will not reach the 404 page until
 * the public paths are named, which happens with the controllers that serve
 * them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // A plain HTTP origin, which costs this class its own Spring context
        // and is worth stating why. server.servlet.session.cookie.secure is
        // true in application.yaml and a real container honours it, so over
        // http:// the session cookie is set and then never sent back -- and
        // the 405 test below needs a session, because that is where Spring
        // Security keeps the CSRF token. Measured: without this line that
        // test answers 403, which is the token missing rather than the method
        // being wrong, and it would have been read as the fallback page
        // failing to render.
        properties = "server.servlet.session.cookie.secure=false")
class ErrorPagesTest {

    @LocalServerPort
    int port;

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            // Two headers a browser sends and java.net.http does not.
            //
            // Accept: text/html is the one that selects the page. Spring
            // Boot's BasicErrorController maps the same path twice -- an HTML
            // method that renders error/<status>.html and a JSON method for
            // API clients -- and content negotiation picks between them. With
            // the default Accept: */* this test received the JSON body and
            // would have reported a missing template as a missing
            // translation.
            //
            // Accept-Language: bg because the assertions below are on
            // Bulgarian text and the JVM default locale decides otherwise.
            return client.send(request
                            .header("Accept", "text/html,application/xhtml+xml")
                            .header("Accept-Language", "bg")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void rendersTheNotFoundPageInTheSiteLayout() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(url("/dev/no-such-page")).GET());

        assertThat(response.statusCode()).isEqualTo(404);
        // The page's own text, from the bundle...
        assertThat(response.body()).contains("Няма такава страница");
        // ...inside the layout, which is the half that distinguishes this
        // from the whitelabel page.
        assertThat(response.body()).contains("5 Good Minutes", "id=\"main\"");
        // And nothing about the request that produced it: an error page that
        // echoes the path is a reflected-XSS hole on the page nobody tests.
        assertThat(response.body()).doesNotContain("no-such-page");
    }

    /**
     * The fallback, {@code error/error.html}, which is what every status
     * without a page of its own resolves to.
     *
     * <p>It is worth a test because the gap it closed was invisible: four
     * named pages existed, so the directory looked finished, while a 405 from
     * a crawler and a 400 from a hand-edited query parameter still rendered
     * Boot's whitelabel page. Page 1's {@code ?page=abc} is the second of
     * those and arrives with the next controller.
     *
     * <p><strong>Reaching a fifth status is harder than it sounds, and the
     * two cheap routes both failed.</strong> A GET against the style guide's
     * POST-only mapping answers 404, not 405, because that controller is
     * {@code @Profile("dev")} and is not registered in the suite at all. A
     * {@code TRACE} cannot be sent: the JDK's HTTP client refuses the method
     * outright. So this does it the honest way -- a POST to the GET-only
     * listing, carrying a real CSRF token, which gets past
     * {@code CsrfFilter} and reaches the dispatcher with no handler for the
     * method.
     *
     * <p>That makes this a round-trip check of the layout's CSRF wiring as
     * well: the token comes out of the {@code _csrf} meta tag the same way an
     * htmx request would take it, so if the meta tag ever stops being
     * rendered, this test answers 403 and says so.
     */
    @Test
    void rendersTheFallbackPageForAStatusWithNoPageOfItsOwn() throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager())
                .build()) {

            // The listing renders the layout, which is what puts the token in
            // the session and the session cookie on the client.
            HttpResponse<String> page = client.send(
                    HttpRequest.newBuilder(url(ActionPaths.LIST))
                            .header("Accept", "text/html")
                            .header("Accept-Language", "bg")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(page.statusCode()).isEqualTo(200);

            HttpResponse<String> wrongMethod = client.send(
                    HttpRequest.newBuilder(url(ActionPaths.LIST))
                            .header("Accept", "text/html,application/xhtml+xml")
                            .header("Accept-Language", "bg")
                            .header(metaContent(page.body(), "_csrf_header"),
                                    metaContent(page.body(), "_csrf"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(wrongMethod.statusCode())
                    .as("a POST with a valid token against a GET-only mapping")
                    .isEqualTo(405);
            assertThat(wrongMethod.body()).contains("Възникна грешка");
            assertThat(wrongMethod.body()).contains("5 Good Minutes", "id=\"main\"");
            assertThat(wrongMethod.body()).doesNotContain("Whitelabel");
        }
    }

    /**
     * The {@code content} of one {@code <meta name="...">} in the layout.
     * A regex rather than a parser because the alternative is a test
     * dependency on an HTML parser to read two attributes.
     */
    private static String metaContent(String html, String name) {
        Matcher matcher = Pattern
                .compile("<meta name=\"" + name + "\" content=\"([^\"]+)\"")
                .matcher(html);
        assertThat(matcher.find()).as("the %s meta tag is in the layout", name).isTrue();
        return matcher.group(1);
    }

    @Test
    void rendersTheForbiddenPageWhenTheCsrfTokenIsMissing() throws Exception {
        // A POST with no token is the realistic way to reach 403 anonymously,
        // and it is the failure an htmx request makes when the layout stops
        // sending the header -- so this is the page that would greet the
        // frontend contributor on the day that breaks.
        HttpResponse<String> response = send(HttpRequest.newBuilder(url("/dev/styleguide/htmx-check"))
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("Нямате достъп до тази страница");
    }
}
