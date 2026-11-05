package com.fivegoodmins.fivegoodmins.shared.email;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * The production transport: Brevo's transactional email API (ADR-005, provider
 * chosen in Rev 19). One class, which is what the {@link EmailSender} interface
 * was for.
 *
 * <p><strong>The API key is a default header, not a parameter.</strong> Brevo
 * authenticates with an {@code api-key} header, so putting it on the client
 * once means no call site can forget it and no log of a request URL can carry
 * it.
 *
 * <p><strong>A 4xx and a 5xx are the same failure here, and both throw.</strong>
 * {@link EmailDeliveryException} for every transport (ADR-005), so the listener
 * catches "the send failed" rather than one exception type per provider. The
 * distinction a caller might want -- permanent rejection against transient
 * outage -- would only be worth modelling if something retried, and ADR-005
 * rejected a retry table at this volume. It names the transactional outbox as
 * what to build when that changes; this is the class that would then grow a
 * retryable/permanent answer.
 *
 * <p><strong>The response body is discarded.</strong> Brevo answers 201 with a
 * {@code messageId}, which is the handle you would quote in a support ticket --
 * and which is worth exactly nothing without somewhere to store it. A column
 * for it is a delivery-log feature; ADR-005 does not have one, so the honest
 * thing is to not pretend by logging an id nobody can look up.
 *
 * <p><strong>Nothing here logs the message body or the recipient's address at
 * INFO.</strong> The failure log carries the address because an operator cannot
 * act on "a send failed"; the body never appears, because a verification or
 * unsubscribe link in a log line is a working link for whoever can read the log
 * (ADR-002 makes the same point about tokens).
 */
class BrevoEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailSender.class);

    private static final String BASE_URL = "https://api.brevo.com";

    private static final String SEND_PATH = "/v3/smtp/email";

    private static final String API_KEY_HEADER = "api-key";

    private final RestClient client;
    private final Sender sender;

    /**
     * Takes a {@link RestClient.Builder} rather than a built client so that the
     * timeouts and the base URL are applied in one place and a test can hand in
     * a builder bound to {@code MockRestServiceServer}. {@link EmailConfig}
     * clones Boot's shared builder before configuring it, so this does not
     * mutate the one everything else would get.
     */
    BrevoEmailSender(RestClient.Builder builder, EmailProperties properties) {
        this.client = builder
                .baseUrl(BASE_URL)
                .defaultHeader(API_KEY_HEADER, properties.brevo().apiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.sender = new Sender(blankToNull(properties.fromName()), properties.from());
    }

    @Override
    public void send(EmailMessage message) {
        try {
            client.post()
                    .uri(SEND_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Payload(
                            sender,
                            List.of(new Recipient(message.to())),
                            message.subject(),
                            message.html(),
                            message.text()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failed) {
            // The provider's own message, which for Brevo carries a code and a
            // reason ("unauthorized", "invalid parameter"), is the one thing
            // worth having in the log: the difference between a revoked key and
            // a malformed recipient is a different afternoon.
            log.error("Brevo refused a message to {}", message.to(), failed);
            throw new EmailDeliveryException("Brevo send failed", failed);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Brevo's request shape. Records with Jackson's defaults, and
     * {@code NON_NULL} so that an absent {@code fromName} is an absent field
     * rather than {@code "name": null} -- which the API rejects on some
     * endpoints and which there is no reason to send either way.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Payload(Sender sender,
                           List<Recipient> to,
                           String subject,
                           String htmlContent,
                           String textContent) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Sender(String name, String email) {
    }

    private record Recipient(String email) {
    }
}
