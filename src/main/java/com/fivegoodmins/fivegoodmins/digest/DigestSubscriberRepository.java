package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.user.AppUser;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Who gets a digest, and the one statement that moves a watermark.
 *
 * <p>A third {@code Repository} over {@code AppUser}, after {@code user/}'s and
 * {@code subscription/}'s, and narrow for the same reason both of those are:
 * Spring Data's bare {@code Repository} marker exposes nothing, so what this
 * package can do to the user table is a projection query and a guarded update.
 * Three interfaces over one table is defensible only while each is this small
 * -- a {@code JpaRepository} here would hand the digest every way there is to
 * write to {@code app_user}, including {@code deleteAll}.
 *
 * <p>Both methods are the digest's own business rather than the user feature's,
 * which is why they are here and not on {@code AppUserRepository}:
 * {@code last_digest_sent_at} is a column only this feature reads or writes,
 * and the rule governing it is ADR-005's.
 */
interface DigestSubscriberRepository extends Repository<AppUser, Long> {

    /**
     * Every account the job may mail, oldest first.
     *
     * <p>The three conditions, each of which has to be here:
     *
     * <ul>
     *   <li><strong>{@code digestEnabled}</strong> -- the preferences page and
     *       the unsubscribe link both write it. Honouring it is the opt-out
     *       working.</li>
     *   <li><strong>{@code enabled}</strong> -- the ban switch. A disabled
     *       account cannot log in, and mailing one daily would be the one way
     *       a banned user still hears from the site.</li>
     *   <li><strong>{@code emailVerifiedAt is not null}</strong> -- an
     *       unconfirmed address is exactly the one not to mail, and ADR-002's
     *       seven-day purge means the row is about to be deleted anyway.</li>
     * </ul>
     *
     * <p>These three are {@code idx_app_user_digest_subscriber}'s partial index
     * condition, written identically so the index can answer the query.
     *
     * <p><strong>Ordered by id, which is not cosmetic.</strong> The job sends
     * one email per subscriber and can fail partway through -- a provider
     * outage, a container replaced mid-run. A stable order means a re-run
     * covers the same people in the same sequence, so a failure is
     * reproducible and a log line naming the last id says how far it got. An
     * unordered query would make "it stopped somewhere" the most precise thing
     * anybody could say.
     *
     * <p><strong>No paging, deliberately.</strong> The whole subscriber list is
     * five columns and the Brevo free tier caps the run at 300 sends a day
     * (ADR-005), so the list is smaller than the emails it produces. The day
     * that stops being true, paging it is the easy half of the problem and the
     * send rate is the hard half.
     */
    @Query("""
            select new com.fivegoodmins.fivegoodmins.digest.DigestSubscriber(
                       u.id, u.email, u.displayName, u.locale, u.lastDigestSentAt)
            from AppUser u
            where u.digestEnabled = true
              and u.enabled = true
              and u.emailVerifiedAt is not null
            order by u.id asc
            """)
    List<DigestSubscriber> findSubscribers();

    /**
     * Moves one subscriber's watermark forward, and only forward.
     *
     * <p><strong>Called only after a send has succeeded</strong> -- that is
     * ADR-005's rule and the reason the digest is restartable. Advancing before
     * the send, or regardless of it, means a provider's bad afternoon silently
     * eats a day of actions for every subscriber, with no record that it
     * happened and nothing to replay.
     *
     * <p><strong>To the cutoff, never to {@code now}.</strong> The cutoff is
     * the upper bound the matching query already used, so the two agree by
     * construction; moving to {@code now} instead would step over the lag
     * window and skip any action that committed during the run.
     *
     * <p><strong>{@code and u.lastDigestSentAt < :cutoff} makes it monotonic in
     * the database, which is the belt to the lock's braces.</strong> If two
     * processes ever run this job at once -- the deploy overlap
     * {@code SchedulingConfig} describes, which {@code JobLockService} exists to
     * prevent -- the later writer cannot drag a watermark backwards and cause
     * the same actions to be mailed a third time tomorrow. It also makes a
     * re-run of a partially completed night a no-op for the subscribers
     * already done, because their watermark is no longer below the cutoff.
     * Cheap, and it covers the case the lock is there to make impossible: two
     * defences, because the one that fails silently is the expensive one.
     *
     * <p>A bulk statement rather than a loaded entity, for the same reasons as
     * {@code subscription/}'s: the job holds an id and a timestamp, nothing
     * needs the other columns, and {@code @UpdateTimestamp} not running means
     * {@code updated_at} does not move -- audit metadata nothing branches on
     * (ADR-019).
     *
     * @return 1 if the watermark moved, 0 if it was already at or past the
     *         cutoff. The caller logs the difference rather than ignoring it:
     *         a zero is either a re-run or the double-send this guard just
     *         prevented, and both are worth seeing.
     */
    /*
     * @Transactional on the method, and this is the one annotation in the
     * feature that was added because the code was run rather than reasoned
     * about.
     *
     * A @Modifying query needs an active transaction, and DigestService
     * deliberately has none -- it takes a short transaction per step precisely
     * so that nothing holds one open across hundreds of calls to a mail
     * provider. Spring Data does not supply one here either: the @Transactional
     * declarations on SimpleJpaRepository cover its own save and delete, not a
     * custom @Query. So without this line the statement throws
     * InvalidDataAccessApiUsageException: "No active transaction for update or
     * delete query".
     *
     * WHY IT IS WORTH THIS MANY WORDS: the throw lands AFTER the send. The
     * email goes out, the watermark does not move, DigestService's catch
     * records a failure -- and tomorrow the same actions are mailed again, and
     * the day after. A duplicate digest for every subscriber every day,
     * forever, which is the exact failure the watermark exists to prevent.
     *
     * And no test saw it. DigestServiceTest mocks this repository;
     * DigestQueriesTest never calls it; every @SpringBootTest in the suite is
     * @Transactional, so a transaction was always already active. It appeared
     * on the first real run against Mailpit. DigestEndToEndTest is the
     * regression test that came out of it, and it is deliberately NOT
     * @Transactional -- which is the property that makes it able to catch this.
     *
     * REQUIRED rather than REQUIRES_NEW: there is no caller with a transaction
     * to suspend, so the two behave identically here, and REQUIRES_NEW would
     * imply an isolation requirement that does not exist.
     */
    @Transactional
    @Modifying
    @Query("""
            update AppUser u
               set u.lastDigestSentAt = :cutoff
             where u.id = :userId
               and u.lastDigestSentAt < :cutoff
            """)
    int advanceWatermark(@Param("userId") Long userId, @Param("cutoff") Instant cutoff);
}
