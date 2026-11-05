package com.fivegoodmins.fivegoodmins.shared.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One token bucket per key per {@link RateLimitScope}, held in memory.
 *
 * <p><strong>In memory, and that is the whole design.</strong> ADR-012 already
 * states this application runs as exactly one instance -- it is what lets the
 * digest use {@code @Scheduled} with no lock -- so a bucket store shared
 * through Redis or Hazelcast would encode that same assumption a second time,
 * in a dependency the architecture explicitly rejected. The consequence is
 * named rather than discovered: <em>every deploy resets every bucket</em>, so
 * an attacker who happens to be mid-run gets a fresh allowance. At one deploy
 * a day against a 15-minute window that is a rounding error; it stops being
 * one the day a second instance exists, and on that day the limiter and the
 * scheduler need the same fix.
 *
 * <p><strong>Caffeine, not a {@code ConcurrentHashMap}.</strong> A map keyed on
 * a client address grows one entry per address ever seen and never shrinks,
 * and {@link ClientAddress} hands out a key per IPv6 {@code /64} -- so the
 * obvious implementation is an out-of-memory error an attacker can schedule.
 * Two bounds close it:
 *
 * <ul>
 * <li><strong>{@code expireAfterAccess} equal to the scope's own window.</strong>
 * Not an arbitrary TTL: after a full window with no request, a greedy-refilling
 * bucket is back at full capacity, so the entry holds no information and
 * dropping it is free. Any shorter and eviction would forgive a limit; any
 * longer and the store keeps rows that say nothing.</li>
 * <li><strong>{@code maximumSize} per scope.</strong> This one is not free:
 * evicting a half-spent bucket resets it, so a flood of distinct keys could in
 * principle push an attacker's own entry out. Caffeine's W-TinyLFU admission
 * policy is what makes that unattractive -- it keeps entries by frequency, and
 * under an attack the most frequently hit key is the attacker's, so the entry
 * they want evicted is the one the cache most wants to keep.</li>
 * </ul>
 *
 * <p><strong>Time comes from the injected {@link Clock}</strong> (ADR-019), by
 * handing Bucket4j a {@link TimeMeter} of our own instead of letting it read
 * {@code System.nanoTime()}. That is not tidiness: a rate limiter is a
 * stopwatch, and without this a test of "twenty attempts, then a refusal, then
 * sixteen minutes later another twenty" would have to actually wait sixteen
 * minutes or assert nothing about the window at all.
 */
@Component
public class RateLimiter {

    private final RateLimitProperties properties;
    private final TimeMeter timeMeter;
    private final Map<RateLimitScope, Cache<String, Bucket>> stores;

    public RateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.timeMeter = new ClockTimeMeter(clock);
        EnumMap<RateLimitScope, Cache<String, Bucket>> stores = new EnumMap<>(RateLimitScope.class);
        for (RateLimitScope scope : RateLimitScope.values()) {
            stores.put(scope, Caffeine.newBuilder()
                    .maximumSize(properties.maxTrackedKeys())
                    .expireAfterAccess(properties.windowOf(scope))
                    .build());
        }
        this.stores = Collections.unmodifiableMap(stores);
    }

    /**
     * Spends one token from {@code key}'s bucket in {@code scope}, creating the
     * bucket on first sight.
     *
     * <p>{@code tryConsumeAndReturnRemaining} rather than {@code tryConsume},
     * for the one thing the probe carries that the boolean does not: how long
     * until a token exists again, which becomes the {@code Retry-After} header
     * and, eventually, a sentence on the page the user lands on.
     */
    Verdict tryConsume(RateLimitScope scope, String key) {
        // No switch here. app.rate-limit.refuse-requests is checked by
        // RateLimitFilter, because it gates request refusal and nothing else --
        // see RateLimitProperties for why reaching the mail cap with it was a
        // hole rather than a convenience.
        Bucket bucket = bucketFor(scope, key);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed()
                ? Verdict.ALLOWED
                : new Verdict(false, Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    /**
     * Greedy refill, not intervally. Both allow the same sustained rate -- the
     * capacity over the window -- and differ only in how the allowance comes
     * back. Intervally returns all of it at once on a boundary, so a user who
     * mistypes their password into an exhausted bucket waits the better part of
     * fifteen minutes; greedy drips one token every forty-five seconds, so they
     * wait forty-five. An attacker gains nothing from that, because what bounds
     * a guessing run is the sustained rate, which is identical.
     */
    private Bucket newBucket(RateLimitScope scope) {
        int capacity = properties.capacityOf(scope);
        Duration window = properties.windowOf(scope);
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, window))
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    /**
     * Whether a registration email may be sent to {@code recipient}, spending
     * one of that address's allowance if so.
     *
     * <p><strong>This is the one bucket that refuses a send rather than a
     * request, and the asymmetry is deliberate.</strong> Its job is to stop
     * {@code /register} being a mail cannon pointed at somebody else's inbox
     * (ADR-002), and the key is therefore an address an attacker can simply
     * type. A bucket like that must never be able to refuse its owner
     * anything: three POSTs naming {@code victim@example.com} would otherwise
     * lock the real owner out of registering for a day, which is precisely the
     * denial-of-service weapon ADR-002 rejects account lockout for. Refusing
     * the send protects the same inbox, costs its owner nothing, and leaves
     * {@code /register}'s deliberately neutral response untouched.
     *
     * <p>Exposed as a question about registration email rather than as the
     * scope and the key, so the mail layer never learns there are scopes.
     *
     * @param recipient the address as stored, already normalized. Case and
     *                  whitespace variants must collapse to one key or the cap
     *                  is bypassed with a capital letter.
     */
    public boolean allowRegistrationEmail(String recipient) {
        return tryConsume(RateLimitScope.REGISTRATION_EMAILS_PER_RECIPIENT, recipient).allowed();
    }

    /**
     * Puts back the token {@link #allowRegistrationEmail} spent, for a send
     * that was attempted and failed.
     *
     * <p><strong>The cap counts mail that reached somebody's inbox, not
     * attempts to hand it to a provider.</strong> Without this, a Brevo outage
     * plus three retries by a user who can see no email leaves that address
     * capped for twenty-four hours having received nothing -- the limiter
     * punishing the person it exists to protect. It is the same rule ADR-005
     * states for the digest watermark, which "moves only after a successful
     * send", and the general form is worth naming: a side effect that bounds a
     * resource commits on success, never on attempt.
     *
     * <p><strong>Consume-then-refund rather than peek-then-consume</strong>,
     * which is the other way to get this right. The mail pool is one thread
     * today, so peeking first is exact -- but ADR-005 says that if the digest
     * ever needs concurrency both pool numbers move together, and on that day a
     * peek becomes a race that over-sends while a consume stays atomic. The
     * refund costs a compensating call on the failure path only.
     *
     * <p>{@code addTokens} saturates at the bucket's capacity, so a refund for
     * a bucket that has since refilled cannot push it above full.
     */
    public void refundRegistrationEmail(String recipient) {
        bucketFor(RateLimitScope.REGISTRATION_EMAILS_PER_RECIPIENT, recipient).addTokens(1);
    }

    private Bucket bucketFor(RateLimitScope scope, String key) {
        return stores.get(scope).get(key, ignored -> newBucket(scope));
    }

    /**
     * Whether the request may proceed, and if not, how long until it may.
     */
    record Verdict(boolean allowed, Duration retryAfter) {

        static final Verdict ALLOWED = new Verdict(true, Duration.ZERO);

        /**
         * {@code Retry-After} is whole seconds, and rounding down would
         * advertise a time at which the answer is still no. Rounds up, and
         * never to zero.
         */
        long retryAfterSeconds() {
            long seconds = retryAfter.plusNanos(999_999_999L).toSeconds();
            return Math.max(1, seconds);
        }
    }

    /**
     * {@link TimeMeter} over the application's {@link Clock}, so that ADR-019
     * holds inside Bucket4j too.
     *
     * <p>{@code isWallClockBased} is {@code true} because that is what a
     * {@code Clock} is, and the honest answer matters: Bucket4j uses it to
     * decide whether a measured interval can be trusted to be monotonic. The
     * cost is that an NTP step moves every window, which for a limiter is a
     * one-off forgiveness of at most the step size.
     */
    private record ClockTimeMeter(Clock clock) implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            Instant instant = clock.instant();
            return TimeUnit.SECONDS.toNanos(instant.getEpochSecond()) + instant.getNano();
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}
