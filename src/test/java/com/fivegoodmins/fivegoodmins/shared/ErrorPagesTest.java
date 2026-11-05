package com.fivegoodmins.fivegoodmins.shared;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Page 19 of the frontend brief: 404 and 403 are real pages in the site
 * layout rather than Boot's whitelabel page.
 *
 * <p><strong>A real server, because MockMvc cannot see this.</strong> An
 * error page is rendered on the ERROR dispatch, and MockMvc does not perform
 * one: the status comes back correctly and the body comes back empty, so a
 * MockMvc test of an error page passes whether the template exists, is
 * missing, or throws halfway through rendering. That was measured here before
 * this file was written as it is.
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
