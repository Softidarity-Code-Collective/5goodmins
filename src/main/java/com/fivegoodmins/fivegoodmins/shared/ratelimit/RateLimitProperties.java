package com.fivegoodmins.fivegoodmins.shared.ratelimit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The numbers behind ADR-002's rate limiting, and the only place they are
 * written down in code.
 *
 * <p><strong>Defaults live here, not in {@code application.yaml}.</strong>
 * Unlike the datasource and the signing key -- absent on purpose, so a missing
 * value fails at startup rather than being silently wrong -- a missing limit
 * has an obviously correct answer, and the deployed site must carry the policy
 * without anyone remembering to configure it. They are nevertheless properties
 * rather than constants, because a limit that cannot be changed without a
 * build is a limit that cannot be relaxed while something is on fire.
 *
 * <p><strong>Why these numbers</strong> (settled with ADR-002's Rev 19 line):
 *
 * <ul>
 * <li><strong>{@code login-attempts = 20} per 15 minutes, per client
 * address.</strong> A person who knows their password needs one attempt and a
 * person who has forgotten it needs about five, so 20 leaves room for four
 * people fumbling behind a single carrier-grade NAT address -- which in
 * Bulgaria is most mobile traffic. For an attacker it caps a run at 80
 * guesses an hour from one address, which makes a thousand-password list a
 * half-day job and a credential-stuffing list of thousands pointless. It is
 * deliberately not tight enough to stop a slow distributed run; that is what
 * ADR-002 names Turnstile for.</li>
 * <li><strong>{@code registrations-per-address = 10} per hour.</strong>
 * Registration is a once-ever act, so any number above one is there purely for
 * shared addresses -- a classroom or an office signing up after a talk. Ten an
 * hour covers that and still stops a single host enumerating.</li>
 * <li><strong>{@code registration-emails-per-recipient = 3} per day.</strong> This is
 * the bucket that matters, and the reason ADR-002 asks for a second one:
 * {@code register} emails the owner of an unverified address once per request,
 * so without a per-address cap the form is a mail cannon aimed at somebody
 * else's inbox. Three a day is enough for a person who really is registering,
 * retrying and registering again, and far too few to be a nuisance.</li>
 * </ul>
 *
 * <p><strong>{@code refuse-requests} is an incident switch, and it is named for
 * exactly what it switches off.</strong> If the limiter ever starts refusing
 * real users -- a stale Cloudflare range collapsing every request onto one
 * bucket is the documented way for that to happen -- turning it off should be
 * one environment variable and a restart rather than a revert and a deploy. It
 * is read by {@link RateLimitFilter} and nowhere else, because that filter is
 * the only thing in the application that refuses a request. It is also what the
 * test suite sets, so that a suite which POSTs the registration form twenty
 * times is not testing the limiter by accident.
 *
 * <p><strong>It does not reach the per-recipient mail cap, and that cap has no
 * switch of its own either.</strong> This is the paragraph
 * {@code RateLimitFilter} points at, so it is worth being exact. The flag was
 * called {@code enabled} and did disarm all three buckets, which was wrong for
 * the reason {@link RateLimitScope} states: two of them refuse a request, one
 * refuses a send. Only an address-keyed bucket can misfire against a real user,
 * because only those are keyed on something identifying the person making the
 * request; the mail cap is keyed on the <em>recipient</em> and cannot refuse a
 * client anything. So the one situation this switch exists for was also the
 * situation in which flipping it turned {@code /register} back into a mail
 * cannon -- with the per-address bucket going off at the same moment, leaving
 * nothing bounding outbound mail at all. A flag that disarmed the cap would be
 * reached for in precisely the wrong moment, so there is none. To relax the cap,
 * change the number; the test suite raises it rather than switching it off, for
 * that reason.
 *
 * <p><strong>{@code ignoreUnknownFields = false}, which is why a stale
 * {@code app.rate-limit.*} variable fails startup instead of doing
 * nothing.</strong> Spring ignores unknown configuration properties, so when
 * {@code enabled} was renamed, any environment still setting
 * {@code APP_RATE_LIMIT_ENABLED} would have bound it to nothing and said
 * nothing -- and the limiter would have come back on, silently, at the next
 * deploy. That is the same class of failure as a missing datasource looking
 * like a dead database, and it gets the same answer this project gives
 * everywhere else: fail at startup, naming the property. The message is
 * literally {@code The elements [app.rate-limit.enabled] were left unbound},
 * and on Railway a container that will not start never takes over from the one
 * already running, so the cost of being wrong here is a refused deploy rather
 * than a broken site. The price is that every key under this prefix must be a
 * component of this record -- which is the point.
 *
 * <p>{@code max-tracked-keys} bounds each scope's bucket store; see
 * {@link RateLimiter} for what eviction costs.
 */
@ConfigurationProperties(prefix = "app.rate-limit", ignoreUnknownFields = false)
@Validated
public record RateLimitProperties(

        @DefaultValue("true") boolean refuseRequests,

        @DefaultValue("20") @Min(1) int loginAttempts,
        @DefaultValue("15m") @NotNull Duration loginWindow,

        @DefaultValue("10") @Min(1) int registrationsPerAddress,
        @DefaultValue("1h") @NotNull Duration registrationsPerAddressWindow,

        @DefaultValue("3") @Min(1) int registrationEmailsPerRecipient,
        @DefaultValue("24h") @NotNull Duration registrationEmailsPerRecipientWindow,

        @DefaultValue("20000") @Min(1) int maxTrackedKeys) {

    int capacityOf(RateLimitScope scope) {
        return switch (scope) {
            case LOGIN_ATTEMPTS -> loginAttempts;
            case REGISTRATIONS_PER_ADDRESS -> registrationsPerAddress;
            case REGISTRATION_EMAILS_PER_RECIPIENT -> registrationEmailsPerRecipient;
        };
    }

    Duration windowOf(RateLimitScope scope) {
        return switch (scope) {
            case LOGIN_ATTEMPTS -> loginWindow;
            case REGISTRATIONS_PER_ADDRESS -> registrationsPerAddressWindow;
            case REGISTRATION_EMAILS_PER_RECIPIENT -> registrationEmailsPerRecipientWindow;
        };
    }
}
