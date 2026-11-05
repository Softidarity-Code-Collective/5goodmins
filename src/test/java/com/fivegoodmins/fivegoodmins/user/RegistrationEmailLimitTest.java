package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.action.ActionSummary;
import com.fivegoodmins.fivegoodmins.action.ActionType;
import com.fivegoodmins.fivegoodmins.shared.email.EmailComposer;
import com.fivegoodmins.fivegoodmins.shared.email.EmailDeliveryException;
import com.fivegoodmins.fivegoodmins.shared.email.EmailMessage;
import com.fivegoodmins.fivegoodmins.shared.email.EmailProperties;
import com.fivegoodmins.fivegoodmins.shared.email.EmailSender;
import com.fivegoodmins.fivegoodmins.shared.ratelimit.RateLimitProperties;
import com.fivegoodmins.fivegoodmins.shared.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-002's per-recipient cap on registration email, which this review moved
 * off the request and onto the send.
 *
 * <p><strong>Its own file, and a unit test rather than a {@code @SpringBootTest},
 * for one reason worth stating.</strong> The cap is process state in a
 * singleton. Enabling it inside {@code RegistrationEmailListenerTest} would
 * share one bucket across that class's methods, which all mail the same
 * address, so the fourth test to run would fail because the third had
 * succeeded. Buckets and a cached context are a bad pair, and the fix is a
 * context that does not exist rather than an ordering nobody can see.
 *
 * <p>The composer is mocked because the decision under test happens strictly
 * before composition: a suppressed send never renders a template, which is
 * itself one of the assertions.
 */
class RegistrationEmailLimitTest {

    private static final String ADDRESS = "georgi@seed.example";
    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");

    /** The shipped default (ADR-002), so this file reads against the real cap. */
    private static final int CAP = 3;

    private MutableClock clock;
    private RecordingSender sender;
    private EmailComposer composer;
    private RegistrationEmailListener listener;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-29T09:00:00Z"));
        sender = new RecordingSender();
        composer = mock(EmailComposer.class);
        when(composer.compose(anyString(), any(), any(), any()))
                .thenAnswer(call -> new EmailMessage(
                        call.getArgument(0), "subject", "<p>html</p>", "text"));

        listener = listenerWith(limits(), sender);
    }

    @Test
    void sendsUpToTheCapAndThenSuppresses() {
        for (int attempt = 1; attempt <= CAP; attempt++) {
            listener.onRegistrationEmail(verification());
            assertThat(sender.sent()).as("after attempt %d", attempt).hasSize(attempt);
        }

        listener.onRegistrationEmail(verification());

        assertThat(sender.sent()).as("the cap is spent").hasSize(CAP);
    }

    /**
     * A suppressed send must not render a template either. Not an optimisation:
     * composing is the only step that touches user-supplied text, and a cap
     * that still did the work would be a cap only on the provider's bill.
     */
    @Test
    void doesNotEvenComposeASuppressedEmail() {
        for (int attempt = 0; attempt <= CAP; attempt++) {
            listener.onRegistrationEmail(verification());
        }

        assertThat(sender.sent()).hasSize(CAP);
        org.mockito.Mockito.verify(composer, org.mockito.Mockito.times(CAP))
                .compose(anyString(), any(), any(), any());
    }

    /**
     * Case and whitespace collapse to one key. The service normalizes before it
     * keys an account on an address, so anything else here is a cap bypassed
     * with a capital letter.
     */
    @Test
    void treatsCaseAndWhitespaceVariantsAsOneRecipient() {
        listener.onRegistrationEmail(verificationTo("Georgi@Seed.Example"));
        listener.onRegistrationEmail(verificationTo("GEORGI@SEED.EXAMPLE"));
        listener.onRegistrationEmail(verificationTo("  georgi@seed.example  "));

        listener.onRegistrationEmail(verificationTo(ADDRESS));

        assertThat(sender.sent()).hasSize(CAP);
    }

    @Test
    void capsEachRecipientSeparately() {
        for (int attempt = 0; attempt <= CAP; attempt++) {
            listener.onRegistrationEmail(verification());
        }
        sender.reset();

        listener.onRegistrationEmail(verificationTo("maya@seed.example"));

        assertThat(sender.sent()).hasSize(1);
    }

    @Test
    void releasesTheAllowanceAfterTheWindow() {
        for (int attempt = 0; attempt <= CAP; attempt++) {
            listener.onRegistrationEmail(verification());
        }
        sender.reset();

        clock.advance(Duration.ofHours(24));
        listener.onRegistrationEmail(verification());

        assertThat(sender.sent()).hasSize(1);
    }

    /**
     * The "you already have an account" mail shares the cap, and must: it is
     * the other thing an unauthenticated POST to {@code /register} can aim at
     * somebody else's inbox, and capping only the verification mail would move
     * the cannon one branch sideways.
     */
    @Test
    void countsBothRegistrationEmailsAgainstOneAllowance() {
        listener.onRegistrationEmail(verification());
        listener.onRegistrationEmail(existingAccount());
        listener.onRegistrationEmail(verification());

        listener.onRegistrationEmail(existingAccount());

        assertThat(sender.sent()).hasSize(CAP);
    }

    /**
     * The welcome email is exempt, and that is a decision rather than an
     * oversight. It cannot be used as a cannon -- reaching it requires a valid
     * signed token for the account, which only the mailbox's reader has -- and
     * capping it would mean a user who confirms after an attacker has spent the
     * allowance is welcomed by silence.
     */
    @Test
    void neverSuppressesTheWelcomeEmail() {
        for (int attempt = 0; attempt <= CAP; attempt++) {
            listener.onRegistrationEmail(verification());
        }
        sender.reset();

        listener.onRegistrationEmail(welcome());

        assertThat(sender.sent()).hasSize(1);
    }

    /**
     * The incident switch must not reach this cap, and this is the regression
     * test for a version in which it did.
     *
     * <p>{@code refuse-requests} exists to recover from the limiter refusing
     * real users, which only the two address-keyed buckets can do. This one is
     * keyed on the recipient and cannot misfire against a client — so the one
     * situation the switch exists for was also the situation in which flipping
     * it turned {@code /register} back into a mail cannon, with the per-address
     * bucket going off at the same moment and nothing left bounding it.
     */
    @Test
    void keepsCappingMailWhenRequestRefusalIsSwitchedOff() {
        RegistrationEmailListener withRefusalOff = listenerWith(
                new RateLimitProperties(false, 20, Duration.ofMinutes(15),
                        10, Duration.ofHours(1), CAP, Duration.ofHours(24), 20_000),
                sender);

        for (int attempt = 0; attempt <= CAP; attempt++) {
            withRefusalOff.onRegistrationEmail(verification());
        }

        assertThat(sender.sent())
                .as("the mail cap has no switch, so it must still bite")
                .hasSize(CAP);
    }

    /**
     * The cap counts mail that reached an inbox, not attempts to hand it to a
     * provider.
     *
     * <p>Without the refund, a Brevo outage plus three retries by a user who
     * can see no email leaves that address capped for twenty-four hours having
     * received nothing — the limiter punishing the person it exists to protect.
     * Same rule as ADR-005's digest watermark, which moves only after a
     * successful send, and the general form is worth naming: a side effect that
     * bounds a resource commits on success, never on attempt.
     */
    @Test
    void doesNotSpendTheAllowanceOnASendThatFailed() {
        // ONE limiter behind both listeners. Two limiters would be two bucket
        // stores, and this test would pass with no refund in the code at all --
        // which is exactly what the first version of it did.
        RateLimiter limiter = new RateLimiter(limits(), clock);
        FailingSender failing = new FailingSender();
        RegistrationEmailListener overABrokenTransport = listener(failing, limiter);

        for (int attempt = 1; attempt <= CAP; attempt++) {
            overABrokenTransport.onRegistrationEmail(verification());
        }
        assertThat(failing.attempts()).as("every one was tried").isEqualTo(CAP);

        // The provider comes back. The address has received nothing, so its
        // whole allowance must still be there.
        RegistrationEmailListener recovered = listener(sender, limiter);
        for (int attempt = 1; attempt <= CAP; attempt++) {
            recovered.onRegistrationEmail(verification());
        }
        recovered.onRegistrationEmail(verification());

        assertThat(sender.sent())
                .as("the whole allowance survived the outage, and the cap still bites after it")
                .hasSize(CAP);
    }

    /**
     * The exemption has to be mirrored on the refund path too: refunding an
     * event that was never charged would hand the recipient a free token every
     * time a welcome email failed to send.
     */
    @Test
    void doesNotRefundAWelcomeEmailItNeverCharged() {
        RateLimiter limiter = new RateLimiter(limits(), clock);

        // Spend most of the allowance on mail that really went out, so that a
        // wrongly refunded welcome has somewhere to show up. Without this the
        // bucket would already be full, addTokens would saturate, and the test
        // would pass whether the exemption was mirrored or not.
        RegistrationEmailListener working = listener(sender, limiter);
        working.onRegistrationEmail(verification());
        working.onRegistrationEmail(verification());

        // Now fail a pile of welcome sends. None of them was charged, so none
        // may be refunded -- the one remaining verification token must still be
        // one, not three.
        RegistrationEmailListener broken = listener(new FailingSender(), limiter);
        for (int attempt = 1; attempt <= CAP * 3; attempt++) {
            broken.onRegistrationEmail(welcome());
        }

        working.onRegistrationEmail(verification());
        working.onRegistrationEmail(verification());

        assertThat(sender.sent())
                .as("two before, one left, and nothing minted by the failed welcomes")
                .hasSize(CAP);
    }

    private RateLimitProperties limits() {
        return new RateLimitProperties(
                true,
                20, Duration.ofMinutes(15),
                10, Duration.ofHours(1),
                CAP, Duration.ofHours(24),
                20_000);
    }

    /** A listener over its own limiter, for the tests that need two of them. */
    private RegistrationEmailListener listenerWith(RateLimitProperties limits, EmailSender target) {
        return listener(target, new RateLimiter(limits, clock));
    }

    /** A listener over a limiter the caller supplies, so buckets can be shared. */
    private RegistrationEmailListener listener(EmailSender target, RateLimiter limiter) {
        return new RegistrationEmailListener(
                composer,
                target,
                new EmailProperties("no-reply@test.invalid", null, "https://test.invalid",
                        new EmailProperties.Brevo(null, Duration.ofSeconds(5), Duration.ofSeconds(10))),
                inlineExecutor(),
                limiter);
    }

    private static RegistrationEmailEvent verification() {
        return verificationTo(ADDRESS);
    }

    private static RegistrationEmailEvent verificationTo(String address) {
        return new RegistrationEmailEvent.Verification(7L, address, "Георги", BULGARIAN, "a-token");
    }

    private static RegistrationEmailEvent existingAccount() {
        return new RegistrationEmailEvent.ExistingAccount(7L, ADDRESS, "Георги", BULGARIAN);
    }

    private static RegistrationEmailEvent welcome() {
        return new RegistrationEmailEvent.Welcome(7L, ADDRESS, "Георги", BULGARIAN,
                List.of(new ActionSummary(42L, "Акция", ActionType.PETITION, "Описание")));
    }

    /**
     * Runs the task on the calling thread. The hand-off to a pool is
     * {@code RegistrationEmailListenerTest}'s subject; here it would only make
     * every assertion a race.
     */
    private static AsyncTaskExecutor inlineExecutor() {
        return new AsyncTaskExecutor() {
            @Override
            public void execute(Runnable task) {
                task.run();
            }
        };
    }

    /** Every send throws, the way a provider outage looks from in here. */
    private static final class FailingSender implements EmailSender {

        private int attempts;

        @Override
        public void send(EmailMessage message) {
            attempts++;
            throw new EmailDeliveryException("the provider is down", new java.io.IOException("refused"));
        }

        int attempts() {
            return attempts;
        }
    }

    private static final class RecordingSender implements EmailSender {

        private final List<EmailMessage> sent = new ArrayList<>();

        @Override
        public void send(EmailMessage message) {
            sent.add(message);
        }

        List<EmailMessage> sent() {
            return List.copyOf(sent);
        }

        void reset() {
            sent.clear();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration by) {
            instant = instant.plus(by);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
