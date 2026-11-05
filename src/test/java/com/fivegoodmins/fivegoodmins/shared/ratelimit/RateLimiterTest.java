package com.fivegoodmins.fivegoodmins.shared.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bucket mechanics, on a clock this test moves by hand.
 *
 * <p>That is the whole reason {@link RateLimiter} hands Bucket4j a
 * {@code TimeMeter} over the injected {@link Clock} (ADR-019) instead of
 * letting it read {@code System.nanoTime()}: the assertions below are about a
 * fifteen-minute window, and without it they would either take fifteen minutes
 * or say nothing about the window at all.
 */
class RateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int CAPACITY = 20;

    private static final String KEY = "203.0.113.7";

    private MutableClock clock;
    private RateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-29T09:00:00Z"));
        limiter = new RateLimiter(properties(), clock);
    }

    private static RateLimitProperties properties() {
        return new RateLimitProperties(
                true,
                CAPACITY, WINDOW,
                10, Duration.ofHours(1),
                3, Duration.ofHours(24),
                20_000);
    }

    @Test
    void allowsExactlyTheCapacityAndThenRefuses() {
        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            assertThat(limiter.tryConsume(RateLimitScope.LOGIN_ATTEMPTS, KEY).allowed())
                    .as("attempt %d of %d", attempt, CAPACITY)
                    .isTrue();
        }

        assertThat(limiter.tryConsume(RateLimitScope.LOGIN_ATTEMPTS, KEY).allowed()).isFalse();
    }

    @Test
    void reportsHowLongUntilTheNextTokenExists() {
        exhaust(RateLimitScope.LOGIN_ATTEMPTS, KEY);

        RateLimiter.Verdict refused = limiter.tryConsume(RateLimitScope.LOGIN_ATTEMPTS, KEY);

        assertThat(refused.retryAfter())
                .isPositive()
                .isLessThanOrEqualTo(WINDOW.dividedBy(CAPACITY));
    }

    /**
     * Greedy refill, asserted as behaviour rather than as a builder call: one
     * token every forty-five seconds, not twenty tokens every fifteen minutes.
     * This is the difference a user who mistyped their password actually feels.
     */
    @Test
    void dripsOneTokenBackPerSliceOfTheWindow() {
        exhaust(RateLimitScope.LOGIN_ATTEMPTS, KEY);

        clock.advance(WINDOW.dividedBy(CAPACITY));

        assertThat(limiter.tryConsume(RateLimitScope.LOGIN_ATTEMPTS, KEY).allowed()).isTrue();
        assertThat(limiter.tryConsume(RateLimitScope.LOGIN_ATTEMPTS, KEY).allowed()).isFalse();
    }

    @Test
    void restoresTheWholeAllowanceAfterAFullWindow() {
        exhaust(RateLimitScope.LOGIN_ATTEMPTS, KEY);

        clock.advance(WINDOW);

        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            assertThat(limiter.tryConsume(RateLimitScope.LOGIN_ATTEMPTS, KEY).allowed())
                    .as("attempt %d after the window", attempt)
                    .isTrue();
        }
    }

    @Test
    void neverAccumulatesBeyondTheCapacity() {
        exhaust(RateLimitScope.LOGIN_ATTEMPTS, KEY);

        clock.advance(WINDOW.multipliedBy(100));

        for (int attempt = 1; attempt <= CAPACITY; attempt++) {
            assertThat(limiter.tryConsume(RateLimitScope.LOGIN_ATTEMPTS, KEY).allowed()).isTrue();
        }
        assertThat(limiter.tryConsume(RateLimitScope.LOGIN_ATTEMPTS, KEY).allowed()).isFalse();
    }

    @Test
    void keepsOneKeysBucketOutOfAnothers() {
        exhaust(RateLimitScope.LOGIN_ATTEMPTS, KEY);

        assertThat(limiter.tryConsume(RateLimitScope.LOGIN_ATTEMPTS, "198.51.100.4").allowed())
                .isTrue();
    }

    /**
     * The same client address appears in two scopes on {@code /register}, and
     * spending one must not spend the other -- otherwise the per-address
     * registration limit would silently also be the login limit.
     */
    @Test
    void keepsOneScopesBucketOutOfAnothers() {
        exhaust(RateLimitScope.LOGIN_ATTEMPTS, KEY);

        assertThat(limiter.tryConsume(RateLimitScope.REGISTRATIONS_PER_ADDRESS, KEY).allowed())
                .isTrue();
    }

    @Test
    void appliesEachScopesOwnCapacity() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(limiter.tryConsume(RateLimitScope.REGISTRATION_EMAILS_PER_RECIPIENT, "a@b.invalid")
                    .allowed()).isTrue();
        }

        assertThat(limiter.tryConsume(RateLimitScope.REGISTRATION_EMAILS_PER_RECIPIENT, "a@b.invalid").allowed())
                .isFalse();
    }

    @Test
    void roundsRetryAfterUpToWholeSecondsAndNeverToZero() {
        assertThat(new RateLimiter.Verdict(false, Duration.ofMillis(1)).retryAfterSeconds())
                .isEqualTo(1);
        assertThat(new RateLimiter.Verdict(false, Duration.ofMillis(1001)).retryAfterSeconds())
                .isEqualTo(2);
        assertThat(new RateLimiter.Verdict(false, Duration.ofSeconds(45)).retryAfterSeconds())
                .isEqualTo(45);
        assertThat(new RateLimiter.Verdict(false, Duration.ZERO).retryAfterSeconds())
                .as("a Retry-After of 0 would advertise a time at which the answer is still no")
                .isEqualTo(1);
    }

    private void exhaust(RateLimitScope scope, String key) {
        for (int attempt = 0; attempt < CAPACITY; attempt++) {
            limiter.tryConsume(scope, key);
        }
    }

    /**
     * A {@link Clock} the test moves forward. Not Mockito: a clock is read many
     * times per assertion here and a stubbed sequence would be the thing under
     * test rather than the limiter.
     */
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

    // -----------------------------------------------------------------------
    // The send-side cap, which is the one bucket that answers a question rather
    // than refusing a request.
    // -----------------------------------------------------------------------

    @Test
    void allowsRegistrationEmailUpToTheRecipientsCapAndThenRefuses() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(limiter.allowRegistrationEmail("ivan@example.invalid"))
                    .as("email %d of 3", attempt)
                    .isTrue();
        }

        assertThat(limiter.allowRegistrationEmail("ivan@example.invalid")).isFalse();
    }

    @Test
    void capsEachRecipientSeparately() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            limiter.allowRegistrationEmail("ivan@example.invalid");
        }

        assertThat(limiter.allowRegistrationEmail("maya@example.invalid")).isTrue();
    }

    /**
     * The recipient cap and the per-address request cap must not share a
     * bucket. They do not share a key either, but a scope mix-up would make a
     * registering client's own allowance decide whether a stranger's inbox is
     * protected.
     */
    @Test
    void keepsTheRecipientCapOutOfTheRequestBuckets() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            limiter.allowRegistrationEmail(KEY);
        }

        assertThat(limiter.tryConsume(RateLimitScope.REGISTRATIONS_PER_ADDRESS, KEY).allowed())
                .isTrue();
    }

    /**
     * The incident switch is not this class's business any more, and the
     * regression test for that is here: {@code refuse-requests = false} must
     * leave the mail cap exactly where it was. The switch is read by
     * {@code RateLimitFilter}, which is the only thing that refuses a request.
     */
    @Test
    void keepsTheMailCapWhenRequestRefusalIsSwitchedOff() {
        RateLimiter withRefusalOff = new RateLimiter(
                new RateLimitProperties(false, 20, WINDOW, 10, Duration.ofHours(1),
                        3, Duration.ofHours(24), 20_000),
                clock);

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(withRefusalOff.allowRegistrationEmail("ivan@example.invalid")).isTrue();
        }

        assertThat(withRefusalOff.allowRegistrationEmail("ivan@example.invalid"))
                .as("the mail cap has no switch, so this one must still refuse")
                .isFalse();
    }

    @Test
    void putsARefundedTokenBack() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            limiter.allowRegistrationEmail("ivan@example.invalid");
        }
        assertThat(limiter.allowRegistrationEmail("ivan@example.invalid")).isFalse();

        limiter.refundRegistrationEmail("ivan@example.invalid");

        assertThat(limiter.allowRegistrationEmail("ivan@example.invalid")).isTrue();
        assertThat(limiter.allowRegistrationEmail("ivan@example.invalid")).isFalse();
    }

    /**
     * A refund cannot mint allowance. {@code addTokens} saturates at the
     * bucket's capacity, so refunding a full bucket — which a retry storm
     * against a recovered provider could do — leaves it full rather than
     * over-full.
     */
    @Test
    void neverRefundsPastTheCapacity() {
        for (int refund = 0; refund < 10; refund++) {
            limiter.refundRegistrationEmail("ivan@example.invalid");
        }

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(limiter.allowRegistrationEmail("ivan@example.invalid")).isTrue();
        }
        assertThat(limiter.allowRegistrationEmail("ivan@example.invalid")).isFalse();
    }
}
