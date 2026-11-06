package com.fivegoodmins.fivegoodmins.user;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the seven-day purge of unverified accounts (ADR-002) once a night.
 *
 * <p>A class containing one annotation and one call, deliberately. The rule
 * and the window belong to {@link RegistrationService}, where they are tested
 * without a scheduler; what belongs here is only <em>when</em>. Putting the
 * deletion itself behind {@code @Scheduled} would make it reachable only by
 * waiting.
 *
 * <p>No {@code @Transactional} here either -- the service method carries it,
 * and a second one on the caller would widen the transaction to no purpose.
 */
@Component
class UnverifiedAccountPurgeJob {

    private final RegistrationService registrations;

    UnverifiedAccountPurgeJob(RegistrationService registrations) {
        this.registrations = registrations;
    }

    /**
     * 03:30 UTC. Explicitly UTC rather than the JVM's default zone, which on
     * Railway is whatever the base image decided: a job whose hour moves with
     * the host is a job that silently changes its relationship to the digest.
     * The digest is deliberately nowhere near this: it runs at 07:00
     * Europe/Sofia, because it is timed for a reader's morning rather than for
     * a quiet server (ADR-005). That leaves at least half an hour in either
     * season, which matters only because the scheduler has one thread
     * (SchedulingConfig) and two jobs in the same minute would queue.
     *
     * <p>Nothing depends on the exact time. The purge is a cleanup with no
     * deadline -- a row deleted at seven days or at eight is the same row --
     * so the only requirement is that it runs when the site is quiet.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    void purgeUnverifiedAccounts() {
        registrations.purgeUnverifiedAccounts();
    }
}
