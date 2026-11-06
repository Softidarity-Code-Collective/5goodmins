package com.fivegoodmins.fivegoodmins.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} (ADR-012).
 *
 * <p><strong>This assumes exactly one instance of the application is
 * running.</strong> Spring's scheduler is in-process and knows nothing about
 * its peers, so a second instance runs every job a second time. It is
 * therefore the single thing in this design that blocks horizontal scaling.
 *
 * <p><strong>The digest no longer relies on that assumption</strong>, because
 * it cannot: {@code DigestJob} takes a Postgres advisory lock through
 * {@code shared/lock/JobLockService} and the loser does nothing (ADR-012,
 * revised). The purge still relies on it and is welcome to -- running it twice
 * deletes nothing the first pass left.
 *
 * <p><strong>"One instance" is false during a deploy, which is why the digest
 * has a lock.</strong> Railway brings the new container up, waits for it to
 * pass its health check, and only then drains the old one, so for that overlap
 * two processes are running the same schedule. A deploy straddling the digest's
 * 07:00 Europe/Sofia slot would otherwise send it twice -- not a theoretical
 * second replica, the ordinary way this application ships. That is what made
 * the lock stop being optional, and ADR-012 was revised rather than worked
 * around: its first version said not to implement ShedLock, which was right
 * while the only job was the purge.
 *
 * <p>The remaining advice is unchanged and cheap: do not deploy near the job
 * times, and prefer jobs that do not care. A lock makes a second instance
 * safe, not useful -- the second process does nothing at all. Making it useful
 * is a work queue, or moving the scheduled work to its own single-replica
 * deployment, and neither is wanted yet.
 *
 * <p>A configuration class of its own rather than the annotation on
 * {@code FivegoodminsApplication}, so that the paragraph above has somewhere
 * to live and so the assumption is a file someone can find.
 *
 * <p>One thread, which is the default. The jobs this application has are a
 * nightly purge at 03:30 UTC and a nightly digest at 07:00 Europe/Sofia; they
 * are not concurrent, and a pool sized for contention that does not exist
 * would only hide it when it starts. The one consequence of sharing a thread,
 * stated because it is the kind of coupling nobody looks for: a purge that
 * somehow hung would hold the digest behind it.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
