package com.fivegoodmins.fivegoodmins.shared.lock;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The scheduler lock against a real PostgreSQL, because an advisory lock has no
 * behaviour at all without one.
 *
 * <p><strong>What this is protecting.</strong> Railway starts the new container,
 * waits for its health check, and only then drains the old one, so every deploy
 * has a window in which two schedulers are live (ADR-012). For the purge that
 * is harmless; for the digest it means every subscriber receives two copies.
 * These tests are the evidence that the second process does nothing.
 *
 * <p>The last test is the one that would otherwise be a comment nobody checks:
 * a session-level advisory lock belongs to the database <em>session</em>, and
 * returning a pooled connection to HikariCP does not end the session. A lock
 * that is not explicitly released therefore travels on that pooled connection
 * and makes the job appear permanently locked, by a holder nobody can identify.
 */
@Import({TestcontainersConfiguration.class, JobLockService.class})
@DataJpaTest
class JobLockServiceTest {

    @Autowired JobLockService locks;
    @Autowired DataSource dataSource;

    @Test
    void runsTheWorkAndReleasesTheLockAfterwards() {
        AtomicInteger runs = new AtomicInteger();

        assertThat(locks.runExclusively(JobLock.DAILY_DIGEST, runs::incrementAndGet)).isTrue();
        // The second call proves the release: a leaked lock would make this
        // false, which is exactly how a missing digest would present itself.
        assertThat(locks.runExclusively(JobLock.DAILY_DIGEST, runs::incrementAndGet)).isTrue();

        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void skipsTheWorkWhileAnotherSessionHoldsTheLock() throws SQLException {
        AtomicInteger runs = new AtomicInteger();

        // A separate connection, standing in for the other container during a
        // deploy overlap. Held open for the duration, because that is what makes
        // it a different session rather than a different statement.
        try (Connection otherProcess = dataSource.getConnection()) {
            assertThat(takeLock(otherProcess, JobLock.DAILY_DIGEST)).isTrue();

            assertThat(locks.runExclusively(JobLock.DAILY_DIGEST, runs::incrementAndGet)).isFalse();
            // Not merely "returned false": the work must not have run. This is
            // the assertion that stands between a deploy and two digests.
            assertThat(runs.get()).isZero();
        }

        // The other session is gone, so the lock is available again -- and that
        // it is released by closing the connection, with no unlock call, is the
        // property ShedLock's lockAtMostFor exists to approximate.
        assertThat(locks.runExclusively(JobLock.DAILY_DIGEST, runs::incrementAndGet)).isTrue();
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void releasesTheLockWhenTheWorkThrows() {
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> locks.runExclusively(JobLock.DAILY_DIGEST, () -> {
                    throw new IllegalStateException("the digest fell over");
                }))
                .withMessage("the digest fell over");

        // The exception propagates, because Spring's scheduler logs an uncaught
        // exception from a @Scheduled method and swallowing it here would turn a
        // broken digest into a silent one. The lock still has to come back.
        AtomicInteger runs = new AtomicInteger();
        assertThat(locks.runExclusively(JobLock.DAILY_DIGEST, runs::incrementAndGet)).isTrue();
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void leavesNoLockBehindOnTheConnectionItReturnedToThePool() throws SQLException {
        locks.runExclusively(JobLock.DAILY_DIGEST, () -> { });

        // THE test for this class's one hazard. pg_locks is global, so this asks
        // the database directly whether anybody anywhere still holds the key --
        // which catches the leak even though the pool has handed the connection
        // on and nothing in Java is holding anything.
        assertThat(advisoryLocksHeldFor(JobLock.DAILY_DIGEST))
                .as("sessions still holding the %s advisory lock", JobLock.DAILY_DIGEST)
                .isZero();
    }

    private static boolean takeLock(Connection connection, JobLock lock) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("select pg_try_advisory_lock(?)")) {
            statement.setLong(1, lock.key());
            try (ResultSet granted = statement.executeQuery()) {
                return granted.next() && granted.getBoolean(1);
            }
        }
    }

    /**
     * How many sessions hold the advisory lock for this key.
     *
     * <p>{@code pg_locks} splits a {@code bigint} advisory key across
     * {@code classid} and {@code objid} as its high and low 32 bits, which is
     * why this is arithmetic rather than a single comparison. The keys in
     * {@link JobLock} are small enough that {@code classid} is zero, but the
     * query says so rather than assuming it.
     */
    private int advisoryLocksHeldFor(JobLock lock) throws SQLException {
        String sql = """
                select count(*)
                  from pg_locks
                 where locktype = 'advisory'
                   and ((classid::bigint << 32) | objid::bigint) = ?
                   and granted
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, lock.key());
            try (ResultSet count = statement.executeQuery()) {
                count.next();
                return count.getInt(1);
            }
        }
    }
}
