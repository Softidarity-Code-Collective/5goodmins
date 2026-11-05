package com.fivegoodmins.fivegoodmins.shared.email;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Who this application's email comes from, and what host its links point at.
 *
 * <p>{@code from} and {@code base-url} are required, with no defaults, and the
 * application does not start without them -- the same decision as the absent
 * datasource in application.yaml and the absent {@code app.token.secret}, for
 * the same reason. A committed default would be a plausible wrong value that fails
 * quietly: mail sent {@code from} an address this domain is not authorised to
 * send for is mail that lands in spam and damages the sending reputation every
 * later digest depends on (ADR-005), and a {@code base-url} left at
 * {@code localhost} puts a dead link in a real mailbox. Both are discovered by
 * a user, days later. Startup naming the missing property is the cheapest
 * version of the same information.
 *
 * <p>Production supplies {@code APP_MAIL_FROM} and {@code APP_MAIL_BASE_URL}.
 * Local runs get them from application-dev.yaml, tests from
 * src/test/resources/application.properties.
 *
 * @param from     the envelope and header sender, an address on a domain whose
 *                 SPF and DKIM records name the provider (ADR-005: plan a day
 *                 for this). No display name here -- that is {@link #fromName}.
 * @param fromName the human-readable sender, shown instead of the address by
 *                 every mail client. Optional, and the same in both languages:
 *                 it is a brand name, not a user-facing string the bundle owns
 *                 (ADR-008).
 * @param baseUrl  the site's public origin, without a trailing slash, used to
 *                 build the absolute links an email has to carry. A nightly job
 *                 and an after-commit listener have no request to read the host
 *                 from, which is why this is configuration and not something
 *                 derived from {@code ServletUriComponentsBuilder}. It must be
 *                 the canonical public origin rather than the host Tomcat sees:
 *                 behind Cloudflare and Railway those differ, and the one in
 *                 the link has to be the one the user can open.
 * @param brevo    the production transport (ADR-005, resolved in Rev 19).
 *                 Optional, unlike the two above, and that asymmetry is the
 *                 point: a missing {@code from} is always a mistake, while a
 *                 missing API key is the ordinary state of a developer machine
 *                 and of the deployed site before the provider was chosen.
 *                 {@link EmailConfig} reads it to decide which
 *                 {@link EmailSender} exists.
 */
@ConfigurationProperties("app.mail")
@Validated
public record EmailProperties(
        @NotBlank String from,
        String fromName,
        @NotBlank String baseUrl,
        @DefaultValue Brevo brevo) {

    public EmailProperties {
        // Trailing slash removed once, here, rather than guarded at every place
        // a link is built. "https://5goodmins.com/" + "/verify-email" is a URL
        // that works in a browser and looks wrong in an email.
        if (baseUrl != null) {
            baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
    }

    /**
     * Brevo's transactional HTTP API, chosen in week 7 on the free-tier limits
     * at that time: 300 emails a day with no monthly ceiling, against Resend's
     * 100 a day and 3,000 a month, and Mailgun's free tier which sends only
     * from a sandbox domain to five verified addresses until a card is on file
     * and so cannot mail an arbitrary registrant at all. The binding constraint
     * for a per-subscriber daily digest is emails <em>per day</em>, which is
     * the number Brevo wins on by three times. The cost, stated because it is
     * real: a clunkier API than Resend's and thinner delivery logs, in a week
     * whose other job is debugging deliverability.
     *
     * <p>An HTTP API rather than Brevo's SMTP relay, as ADR-005 requires:
     * outbound SMTP ports are blocked or throttled on several hosts, and HTTP
     * returns a delivery outcome rather than a queued-and-hope.
     *
     * @param apiKey         {@code APP_MAIL_BREVO_API_KEY} in production, and
     *                       absent everywhere else. Never committed, for the
     *                       same reason as the signing key: a published key is
     *                       a stranger's ability to send mail as this domain,
     *                       which costs the sending reputation that took a week
     *                       to build.
     * @param connectTimeout how long to wait for the provider to answer at all.
     * @param readTimeout    how long to wait for it to finish answering. Both
     *                       are short and both are mandatory, because the mail
     *                       pool is <strong>one thread</strong> behind a
     *                       fifty-deep queue (ADR-005): a provider that accepts
     *                       a connection and then stops talking would, with the
     *                       JDK's default of no timeout, block that thread
     *                       forever and turn a slow afternoon at Brevo into
     *                       fifty lost emails and then silence.
     */
    public record Brevo(
            String apiKey,
            @DefaultValue("5s") @NotNull Duration connectTimeout,
            @DefaultValue("10s") @NotNull Duration readTimeout) {

        boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
