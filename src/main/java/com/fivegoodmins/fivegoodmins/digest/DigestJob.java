package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.shared.lock.JobLock;
import com.fivegoodmins.fivegoodmins.shared.lock.JobLockService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the daily digest once a morning, on one process (ADR-005, ADR-012).
 *
 * <p>Two annotations and one call, like {@code UnverifiedAccountPurgeJob}: the
 * rule and the watermark belong to {@link DigestService}, where they are tested
 * without a scheduler, and what belongs here is only <em>when</em> and
 * <em>how many at a time</em>. Putting the send loop behind {@code @Scheduled}
 * would make it reachable only by waiting until morning.
 *
 * <p>No {@code @Transactional}, deliberately and emphatically: see
 * {@link DigestService}, which takes a short transaction per step precisely so
 * that nothing holds one open across a few hundred calls to a mail provider.
 */
@Component
class DigestJob {

    private final DigestService digests;
    private final JobLockService locks;

    DigestJob(DigestService digests, JobLockService locks) {
        this.digests = digests;
        this.locks = locks;
    }

    /**
     * 07:00 Europe/Sofia.
     *
     * <p><strong>The reader's zone, not UTC, which is the opposite of the
     * purge's choice and is right for the opposite reason.</strong> The purge
     * has no deadline -- a row deleted at seven days or at eight is the same
     * row -- so it runs at a fixed UTC hour and nothing about it cares. This
     * email has a human deadline: it is called 5 Good Minutes, it asks for five
     * minutes of somebody's morning, and it wants to be at the top of the inbox
     * when they first look. Pinned to UTC instead, it would arrive at 07:00
     * local in winter and 08:00 in summer, drifting an hour twice a year
     * against the one thing it is timed for.
     *
     * <p>This is not the implicit-default-zone trap the purge's comment warns
     * about. That objection is to a job whose hour moves with whatever zone the
     * Railway base image happens to have; naming a zone explicitly is the fix
     * for it, and {@code Europe/Sofia} is as explicit as {@code UTC}. The
     * audience is one country (ADR-008: the site is Bulgarian-first), so there
     * is a single right answer rather than a compromise.
     *
     * <p><strong>Clear of the purge at 03:30 UTC</strong>, which is 05:30 or
     * 06:30 Sofia depending on the season -- so at worst thirty minutes before
     * this. Enough: the purge is a single {@code DELETE}. It matters at all
     * only because {@code SchedulingConfig} gives the scheduler one thread, so
     * two jobs at the same minute would queue rather than overlap, and a purge
     * that somehow hung would hold the digest behind it.
     *
     * <p>Six fields, so the leading {@code 0} is seconds: Spring's cron is not
     * the five-field Unix one, and "0 7 * * *" here would mean seven minutes
     * past every hour.
     */
    @Scheduled(cron = "0 0 7 * * *", zone = "Europe/Sofia")
    void sendDailyDigest() {
        // The lock, not the send, is the first thing. During a Railway deploy
        // two containers are briefly live with the same schedule
        // (SchedulingConfig), and this is the one job for which that means
        // every subscriber receives two copies -- so the loser of the lock does
        // nothing at all and says so in its own log.
        locks.runExclusively(JobLock.DAILY_DIGEST, digests::sendDailyDigest);
    }
}
