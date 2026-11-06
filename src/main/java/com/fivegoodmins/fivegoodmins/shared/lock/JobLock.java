package com.fivegoodmins.fivegoodmins.shared.lock;

/**
 * The named locks this application takes, and the integers Postgres knows them
 * by.
 *
 * <p><strong>The key is written out, never derived from the name.</strong>
 * {@code pg_advisory_lock} takes a {@code bigint}, so a name has to become a
 * number somehow, and {@code name().hashCode()} is the obvious way and the
 * wrong one: two lock names that collide would silently share one lock, each
 * blocking the other for reasons neither names, and nothing would ever report
 * it. A literal per constant cannot collide without somebody typing the same
 * number twice in a file whose whole content is the list of them.
 *
 * <p><strong>These numbers are shared state across every process that talks to
 * the database</strong>, which includes the old container and the new one
 * during a Railway deploy -- the overlap this mechanism exists for. They are
 * therefore as much a wire format as a {@code TokenPurpose} constant: changing
 * one while two versions are running means the two versions do not exclude
 * each other, which is the exact failure the lock is for. Add constants
 * freely; renumber one only when nothing else is deployed.
 *
 * <p>Advisory locks live in a single global {@code bigint} namespace per
 * database, shared with anything else that might use them -- there is nothing
 * else here, and the numbers start high enough to be obviously deliberate
 * rather than near whatever a library might pick.
 */
public enum JobLock {

    /**
     * The nightly digest (ADR-005, ADR-012).
     *
     * <p>The one job in this application that must not run twice. The purge
     * does not care -- running it again deletes nothing the first pass left --
     * but a second digest run would mail every subscriber a second copy,
     * because the first run's watermark advance and the second run's matching
     * query are not in the same transaction and cannot be.
     *
     * <p><strong>And "two processes" is the ordinary case, not a hypothetical
     * second replica.</strong> Railway starts the new container, waits for its
     * health check, and only then drains the old one, so every deploy has an
     * overlap in which two schedulers are live. A deploy straddling the send
     * time is all it takes.
     */
    DAILY_DIGEST(900_001L);

    private final long key;

    JobLock(long key) {
        this.key = key;
    }

    long key() {
        return key;
    }
}
