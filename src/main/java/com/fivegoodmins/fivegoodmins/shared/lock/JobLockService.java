package com.fivegoodmins.fivegoodmins.shared.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runs a scheduled job on at most one process at a time, using a PostgreSQL
 * session-level advisory lock.
 *
 * <p><strong>Why this exists now rather than "when it matters" (ADR-012,
 * revised).</strong> ADR-012 accepted one-instance scheduling for the MVP and
 * said not to implement ShedLock yet, which was right while the only job was
 * the unverified-account purge: running that twice deletes nothing the first
 * pass left. The digest is the job that breaks the assumption. Railway brings
 * the new container up, waits for its health check and only then drains the
 * old one, so <strong>every deploy has a window in which two schedulers are
 * live</strong> -- and a deploy straddling the send time means every
 * subscriber gets two copies. That is not a second replica somebody might add
 * one day; it is the ordinary way this application ships.
 *
 * <p><strong>Postgres advisory lock, not ShedLock, and the deciding argument is
 * {@code lockAtMostFor}.</strong> ShedLock works by holding a row, which a
 * crashed process cannot release -- so every lock needs a duration after which
 * the row is considered stale, and that duration is a guess. Too short and the
 * second process starts while the first is still sending, which is the
 * double-send the lock was added to prevent; too long and a container killed
 * mid-run blocks tomorrow's digest as well. An advisory lock has no such
 * number because it is held by the <em>connection</em>: if the process dies,
 * the backend dies, and Postgres releases the lock with it. One SQL function,
 * no dependency, no table, no migration, and no guess.
 *
 * <p>What it costs, stated: it is Postgres-specific, and it holds one pooled
 * connection for the duration of the job. For a nightly job against a
 * ten-connection pool that is affordable; it is the reason this is not the
 * answer for a short job that runs every minute.
 *
 * <p><strong>Session-level, not transaction-level, and that is forced.</strong>
 * {@code pg_try_advisory_xact_lock} releases at the end of its transaction,
 * which would be no use here: the digest deliberately is not one transaction,
 * because it makes an HTTP call per subscriber and commits each watermark
 * separately (ADR-005). A lock scoped to a transaction that must not exist
 * cannot guard the job.
 *
 * <p><strong>The trap this class exists to contain.</strong> A session-level
 * lock belongs to the database session, and closing a pooled
 * {@link Connection} does not end the session -- it returns it to HikariCP for
 * the next caller. So a lock that is not explicitly released <em>survives the
 * close</em> and travels on that pooled connection, and the next night's run
 * finds the job permanently locked by a session nobody can identify. Hence
 * {@code pg_advisory_unlock_all()} in a {@code finally}, before the close, and
 * hence the fact that no caller is trusted to do this themselves: the lock is
 * taken and released by this one method and the job is handed in as a
 * {@link Runnable}.
 *
 * <p>{@code pg_advisory_unlock_all} rather than unlocking the one key: it
 * leaves the session with no advisory locks at all, so a bookkeeping mistake
 * here cannot be what pollutes the pool. This connection holds exactly the one
 * lock, so the broader call does the same thing with a weaker precondition.
 */
@Service
public class JobLockService {

    private static final Logger log = LoggerFactory.getLogger(JobLockService.class);

    /**
     * Returns true if the lock was granted, false if another session holds it.
     * Never blocks -- which is the behaviour wanted for a scheduled job, where
     * waiting for the other process to finish in order to immediately do the
     * same work again is the failure mode rather than the fix.
     */
    private static final String TRY_LOCK = "select pg_try_advisory_lock(?)";

    /** Releases every session-level advisory lock this session holds. */
    private static final String UNLOCK_ALL = "select pg_advisory_unlock_all()";

    private final DataSource dataSource;

    JobLockService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs {@code work} if this process can take {@code lock}, and skips it if
     * another process holds it.
     *
     * <p><strong>Skips rather than waits.</strong> A blocking
     * {@code pg_advisory_lock} would queue the second container behind the
     * first and then run the whole digest again the moment the first finished
     * -- every subscriber mailed twice, a few minutes apart, which is the
     * outcome this is here to prevent rather than a slower version of success.
     * The right thing for a daily job is for the loser to do nothing: the work
     * is being done by somebody.
     *
     * <p><strong>A {@code Runnable}, so the lock cannot be forgotten.</strong>
     * The alternative shape -- {@code tryLock()} and {@code unlock()} for the
     * caller to pair up -- is one early return away from a leaked session-level
     * lock on a pooled connection, which is this class's whole hazard. Nothing
     * outside this method ever holds the connection.
     *
     * <p><strong>An exception from {@code work} propagates,</strong> after the
     * lock is released. The caller is a {@code @Scheduled} method, and Spring's
     * scheduler logs an uncaught exception from one and goes on to the next
     * run; swallowing it here would turn a broken digest into a silent one.
     *
     * @return whether the work ran. False means another process had the lock,
     *         and is not an error: during a deploy overlap it is the expected
     *         answer for one of the two containers.
     * @throws IllegalStateException if the lock cannot be taken because the
     *         database is unreachable. Distinct from a refused lock on purpose
     *         -- "somebody else is doing it" and "there is no database" are not
     *         the same night.
     */
    public boolean runExclusively(JobLock lock, Runnable work) {
        // One connection, held for the whole job, because the lock lives on the
        // session rather than on the statement.
        try (Connection connection = dataSource.getConnection()) {
            if (!tryLock(connection, lock)) {
                log.info("skipped {}: another process holds the lock", lock);
                return false;
            }
            try {
                work.run();
                return true;
            } finally {
                // Before the close, which is the point. try-with-resources
                // would otherwise hand a locked session back to the pool.
                unlockAll(connection, lock);
            }
        } catch (SQLException unreachable) {
            throw new IllegalStateException("could not acquire the " + lock + " lock", unreachable);
        }
    }

    private static boolean tryLock(Connection connection, JobLock lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK)) {
            statement.setLong(1, lock.key());
            try (ResultSet granted = statement.executeQuery()) {
                // pg_try_advisory_lock always returns exactly one row.
                return granted.next() && granted.getBoolean(1);
            }
        }
    }

    /**
     * Logs rather than throws, and is called from a {@code finally}: a throw
     * here would replace whatever the job was failing with, and the job's
     * exception is the one worth seeing.
     *
     * <p>If this does fail, the connection is almost certainly already dead --
     * in which case the backend is gone and Postgres has released the lock
     * anyway. The residual risk is a live connection returned to the pool still
     * holding the lock, which would skip tomorrow's run; HikariCP retires
     * connections at {@code maxLifetime} (thirty minutes by default), so even
     * that heals long before a daily job needs the lock again. Worth an
     * {@code error} rather than a {@code warn} because the symptom -- one
     * missing digest -- is otherwise unattributable.
     */
    private static void unlockAll(Connection connection, JobLock lock) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(UNLOCK_ALL);
        } catch (SQLException failed) {
            log.error("failed to release the {} lock; this session may hold it until the pool retires "
                    + "the connection", lock, failed);
        }
    }
}
