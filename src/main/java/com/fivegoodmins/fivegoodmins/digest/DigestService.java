package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.action.ActionPaths;
import com.fivegoodmins.fivegoodmins.shared.email.EmailComposer;
import com.fivegoodmins.fivegoodmins.shared.email.EmailSender;
import com.fivegoodmins.fivegoodmins.shared.email.EmailTemplate;
import com.fivegoodmins.fivegoodmins.shared.email.EmailProperties;
import com.fivegoodmins.fivegoodmins.subscription.SubscriptionPaths;
import com.fivegoodmins.fivegoodmins.subscription.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The daily digest (ADR-005): for every subscriber, what has been published
 * since the last time they were told, matching what they follow.
 *
 * <p>ADR-005's nightly job, and the watermark pattern is the whole design:
 *
 * <pre>
 * cutoff = now - lag
 * for each verified, enabled subscriber:
 *     actions = published, publishedAt in (watermark, cutoff], not expired,
 *               matching a followed category AND a followed region
 *     if non-empty: render, send, then watermark = cutoff
 * </pre>
 *
 * <p><strong>The watermark moves only after the send succeeds.</strong> That
 * one ordering is what makes this restartable and is the single most
 * consequential line in the class. Moving it first -- or in a
 * {@code finally} -- means a provider outage silently eats a day of actions for
 * everybody, with nothing recorded and nothing to replay. Moving it after
 * means a failed send is retried tomorrow, automatically, with the backlog
 * intact; a job that does not run for two days sends two days of actions on the
 * third. No queue, no retry table, no per-user notification rows.
 *
 * <p><strong>Sent on this thread, synchronously, and not through the mail
 * pool</strong> -- which is the opposite of {@code RegistrationEmailListener}
 * and is deliberate. Two reasons, and both would be bugs the other way. The
 * pool is one thread behind a fifty-deep queue (ADR-005), sized for the
 * occasional registration: handing it three hundred digests would fill it and
 * the rest would be rejected and dropped. And the watermark may only advance
 * after a <em>successful</em> send, so this code has to know the outcome --
 * which a fire-and-forget submit cannot tell it. The latency argument that
 * puts registration email on a pool does not apply here either: there is no
 * HTTP response waiting and no timing oracle to close, because nobody is
 * watching a scheduled job with a stopwatch.
 *
 * <p><strong>One subscriber's failure does not end the run.</strong> Each is
 * sent inside its own try/catch, so a single bad address or one rejected
 * request costs that person a day and nobody else anything. The alternative --
 * letting it propagate -- would mean the first failure silently cancelled every
 * subscriber after it in id order, which is both the wrong outcome and an
 * unfair one.
 *
 * <p><strong>Not {@code @Transactional}, anywhere in this class, and that is
 * load-bearing.</strong> A transaction around the run would hold one open
 * across hundreds of HTTP calls to a mail provider -- minutes of an idle
 * transaction pinning a connection and blocking vacuum. Each piece takes its
 * own short transaction instead: the subscriber list, then per subscriber the
 * filter, the matching query and the watermark advance. The send itself is
 * deliberately in no transaction at all, because there is nothing it could roll
 * back: an email cannot be unsent.
 */
@Service
public class DigestService {

    private static final Logger log = LoggerFactory.getLogger(DigestService.class);

    private static final EmailTemplate DIGEST =
            new EmailTemplate("email/digest", "email.digest.subject");

    /**
     * The lag window: how far short of {@code now} the job stops looking, and
     * how far the watermark then advances.
     *
     * <p><strong>Five minutes, and the reason is transaction visibility rather
     * than caution.</strong> {@code now()} in Postgres is transaction
     * <em>start</em> time, so an action whose publish transaction began at
     * 06:59:59 and committed at 07:00:02 is invisible to a job that started at
     * 07:00:00 -- and that job would move the watermark past it, so the row is
     * never mailed to anybody, ever. Excluding the last five minutes and
     * advancing the watermark only to that same cutoff closes the gap: the row
     * is below the cutoff tomorrow and above the watermark, so it goes out one
     * day later instead of never.
     *
     * <p>The cost is up to five minutes of latency in a job that runs once a
     * day, which is no cost at all. Five rather than one because the quantity
     * being covered is the longest a publish transaction might take, and an
     * admin approving an action on a slow connection is not bounded by
     * anything tighter.
     *
     * <p>A constant rather than a configuration property: a value that is
     * wrong is wrong in production and nowhere else, and there is no
     * environment that wants a different one. ADR-019's rule is about
     * <em>reading</em> the clock, not about the arithmetic, so this being fixed
     * costs no testability -- the tests pass their own instants in.
     */
    private static final Duration LAG = Duration.ofMinutes(5);

    private final DigestSubscriberRepository subscribers;
    private final DigestQueries digests;
    private final SubscriptionService subscriptions;
    private final EmailComposer composer;
    private final EmailSender sender;
    private final EmailProperties properties;
    private final Clock clock;

    DigestService(DigestSubscriberRepository subscribers,
                  DigestQueries digests,
                  SubscriptionService subscriptions,
                  EmailComposer composer,
                  EmailSender sender,
                  EmailProperties properties,
                  Clock clock) {
        this.subscribers = subscribers;
        this.digests = digests;
        this.subscriptions = subscriptions;
        this.composer = composer;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Sends today's digest to everybody who is due one.
     *
     * <p>Public and callable without a scheduler, which is what makes the
     * watermark logic testable: {@code DigestJob} is one annotation and one
     * call to this, so nothing here needs a clock to reach a particular hour
     * (ADR-012's shape, same as the purge job).
     *
     * <p><strong>The clock is read exactly once,</strong> at the top, and
     * {@code now} and {@code cutoff} are then passed down. Reading it per
     * subscriber would give the three hundredth a different cutoff from the
     * first -- so two subscribers could straddle a publication and the
     * watermarks would no longer mean one consistent thing across the run
     * (ADR-019).
     *
     * @return what happened, for the log line and for a test to assert on.
     */
    public DigestRun sendDailyDigest() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(LAG);

        List<DigestSubscriber> due = subscribers.findSubscribers();
        int sent = 0;
        int empty = 0;
        int failed = 0;

        for (DigestSubscriber subscriber : due) {
            switch (sendTo(subscriber, cutoff, now)) {
                case SENT -> sent++;
                case NOTHING_NEW -> empty++;
                case FAILED -> failed++;
            }
        }

        DigestRun run = new DigestRun(due.size(), sent, empty, failed);
        // One line per run at INFO, with the whole shape of it. The number that
        // matters operationally is `failed`: those subscribers kept their
        // watermark and will get the same content tomorrow, so a persistent
        // failure shows up as the same count every morning rather than as
        // silence.
        log.info("daily digest: {} subscribers, {} sent, {} with nothing new, {} failed",
                run.subscribers(), run.sent(), run.nothingNew(), run.failed());
        return run;
    }

    /**
     * One subscriber. Never throws: the outcome is the return value, so the run
     * continues.
     */
    private Outcome sendTo(DigestSubscriber subscriber, Instant cutoff, Instant now) {
        try {
            List<DigestAction> actions = digests.digestFor(
                    subscriber.watermark(), cutoff, now,
                    // The one place "an empty selection means everything" is
                    // decided lives in subscription/, and the digest asks for
                    // it rather than reimplementing it in the query that
                    // matters most (ADR-023).
                    subscriptions.effectiveFilterFor(subscriber.id()));

            if (actions.isEmpty()) {
                // No email, and -- deliberately -- no watermark advance either.
                // ADR-005's loop only moves it on a send, and leaving it put is
                // what lets somebody who later follows another category
                // receive the actions published in the meantime rather than
                // having been silently stepped over. The cost is that an
                // inactive subscriber's query range grows: it is
                // "everything published since their last actual digest", which
                // at this catalogue size is nothing, and which the partial
                // index on (published_at desc, id desc) answers as a range
                // scan. Recorded in ADR-005's open points as the thing to
                // revisit if the catalogue ever outgrows it.
                return Outcome.NOTHING_NEW;
            }

            sender.send(compose(subscriber, actions));

            int moved = subscribers.advanceWatermark(subscriber.id(), cutoff);
            if (moved == 0) {
                // The guarded update declined: this subscriber's watermark is
                // already at or past the cutoff, which means another process
                // did this night's work for them. Not an error -- the email
                // went out and the row is correct -- but it is the only
                // evidence that two runs overlapped, so it is worth a line.
                // JobLockService exists to make this impossible; this is what
                // notices if it ever fails to.
                log.warn("watermark for account {} was already at or past the cutoff; "
                        + "a concurrent run is the likely cause", subscriber.id());
            }
            return Outcome.SENT;
        } catch (RuntimeException failed) {
            // The address is never logged -- the id is (ADR-005). The watermark
            // is untouched, so tomorrow's run carries today's actions as well.
            log.error("failed to send the daily digest to account {}", subscriber.id(), failed);
            return Outcome.FAILED;
        }
    }

    private com.fivegoodmins.fivegoodmins.shared.email.EmailMessage compose(
            DigestSubscriber subscriber, List<DigestAction> actions) {
        return composer.compose(
                subscriber.email(),
                subscriber.language(),
                DIGEST,
                Map.of("displayName", subscriber.displayName(),
                        "actions", actions,
                        "actionsUrl", url(ActionPaths.LIST),
                        "unsubscribeUrl", unsubscribeUrl(subscriber.id())),
                // The count in the subject, so a month of digests does not
                // collapse into one Gmail thread. A number, never the display
                // name: a subject is a header, and a header takes a line break
                // as the end of itself.
                new Object[]{actions.size()});
    }

    /**
     * The unsubscribe link, and <strong>only this email carries one</strong>
     * (ADR-005, frontend brief §4). The three transactional emails do not: an
     * unsubscribe link on a verification email is an invitation to opt out of
     * the thing the reader is in the middle of doing.
     *
     * <p>The token is issued by {@code subscription/}, which is the feature
     * that honours it, so the purpose is named once. A link signed under the
     * wrong purpose fails to verify a day later in somebody's mailbox rather
     * than here.
     *
     * <p>{@code UriComponentsBuilder} rather than concatenation, so the token
     * is encoded by something that knows the rules -- today's tokens are
     * digits, dots and Base64url and would survive either way, which is a
     * property of the current format and not a reason to hand-roll it.
     */
    private String unsubscribeUrl(long userId) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path(SubscriptionPaths.UNSUBSCRIBE)
                .queryParam(SubscriptionPaths.TOKEN_PARAMETER, subscriptions.unsubscribeToken(userId))
                .build()
                .encode()
                .toUriString();
    }

    private String url(String path) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl()).path(path).build().encode().toUriString();
    }

    /** What happened for one subscriber. Private: the counts are the API. */
    private enum Outcome { SENT, NOTHING_NEW, FAILED }
}
