package com.fivegoodmins.fivegoodmins.shared.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The provider call, against a mock HTTP server rather than Brevo.
 *
 * <p>No test sends real mail, and this is the class where that rule is easiest
 * to break: a single forgotten API key in a properties file and the suite
 * starts mailing strangers from the project's own domain, burning the sending
 * reputation ADR-005 budgets a week for. {@code MockRestServiceServer} means
 * there is no socket to open.
 *
 * <p>What is worth asserting is the request shape, because it is the one thing
 * that cannot be found out from a type error: Brevo wants
 * {@code htmlContent}/{@code textContent} and a {@code to} array of objects,
 * and a typo in any of those names is a 400 discovered in production.
 */
class BrevoEmailSenderTest {

    private static final EmailMessage MESSAGE = new EmailMessage(
            "ivan@example.invalid",
            "Потвърдете имейл адреса си",
            "<p>Здравейте</p>",
            "Здравейте");

    private RestClient.Builder builder;
    private MockRestServiceServer brevo;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        brevo = MockRestServiceServer.bindTo(builder).build();
    }

    private static EmailProperties properties(String fromName) {
        return new EmailProperties(
                "no-reply@5goodmins.com",
                fromName,
                "https://5goodmins.com",
                new EmailProperties.Brevo("test-key", Duration.ofSeconds(5), Duration.ofSeconds(10)));
    }

    @Test
    void postsTheMessageToBrevosTransactionalEndpoint() {
        brevo.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("api-key", "test-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sender.email").value("no-reply@5goodmins.com"))
                .andExpect(jsonPath("$.sender.name").value("5 Good Minutes"))
                .andExpect(jsonPath("$.to[0].email").value("ivan@example.invalid"))
                .andExpect(jsonPath("$.subject").value("Потвърдете имейл адреса си"))
                .andExpect(jsonPath("$.htmlContent").value("<p>Здравейте</p>"))
                .andExpect(jsonPath("$.textContent").value("Здравейте"))
                .andRespond(withSuccess("{\"messageId\":\"<abc@brevo>\"}", MediaType.APPLICATION_JSON));

        new BrevoEmailSender(builder, properties("5 Good Minutes")).send(MESSAGE);

        brevo.verify();
    }

    /**
     * {@code "name": null} is a field Brevo has no use for and rejects on some
     * endpoints, so an absent display name must be an absent key.
     */
    @Test
    void omitsTheSenderNameEntirelyWhenThereIsNone() {
        brevo.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(jsonPath("$.sender.name").doesNotExist())
                .andExpect(jsonPath("$.sender.email").value("no-reply@5goodmins.com"))
                .andRespond(withSuccess());

        new BrevoEmailSender(builder, properties("   ")).send(MESSAGE);

        brevo.verify();
    }

    /**
     * A revoked or mistyped key. It must throw, not return: ADR-005 is explicit
     * that a send which did not happen must never be mistakable for one that
     * did, which is why {@link EmailSender#send} has no return value to ignore.
     */
    @Test
    void turnsARejectedKeyIntoAnEmailDeliveryException() {
        brevo.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatExceptionOfType(EmailDeliveryException.class)
                .isThrownBy(() -> new BrevoEmailSender(builder, properties("5 Good Minutes")).send(MESSAGE))
                .withCauseInstanceOf(org.springframework.web.client.RestClientException.class);
    }

    @Test
    void turnsAProviderOutageIntoTheSameException() {
        brevo.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatExceptionOfType(EmailDeliveryException.class)
                .isThrownBy(() -> new BrevoEmailSender(builder, properties(null)).send(MESSAGE));
    }

    /**
     * A wrong path is the mistake a refactor makes, and it looks like an outage
     * rather than a bug unless something pins it.
     */
    @Test
    void treatsAMissingEndpointAsAFailedSendRatherThanASilentSuccess() {
        brevo.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withResourceNotFound());

        assertThatExceptionOfType(EmailDeliveryException.class)
                .isThrownBy(() -> new BrevoEmailSender(builder, properties(null)).send(MESSAGE))
                .satisfies(failed -> assertThat(failed.getMessage()).contains("Brevo"));
    }
}
