package com.fivegoodmins.fivegoodmins.subscription;

import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import com.fivegoodmins.fivegoodmins.shared.token.SignedTokenService;
import com.fivegoodmins.fivegoodmins.shared.token.TokenPurpose;
import com.fivegoodmins.fivegoodmins.shared.token.TokenVerification;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * What a user follows, and the two ways it changes: the preferences page
 * (page 13, signed in) and the unsubscribe link (page 9, signed out).
 *
 * <p>The two halves of one decision, in one class on purpose. They write the
 * same column through the same repository, and keeping them apart would be two
 * places to change the day the digest grows a second frequency or a pause.
 *
 * <p><strong>No {@code Clock} here, which is unusual in this codebase.</strong>
 * Nothing this class does is time-dependent: a subscription has no start, no
 * expiry and no schedule, and the token lifetimes are
 * {@link SignedTokenService}'s business. The digest is where the clock
 * matters, and it has one (ADR-019).
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptions;
    private final SignedTokenService tokens;

    SubscriptionService(SubscriptionRepository subscriptions, SignedTokenService tokens) {
        this.subscriptions = subscriptions;
        this.tokens = tokens;
    }

    /**
     * What page 13 shows: the selection as stored, plus the digest switch.
     *
     * <p>Read in one transaction, returned as values. The two queries are
     * separate rather than one join because a join across two element
     * collections is a cross product -- ten categories and thirty regions
     * would come back as three hundred rows describing sixty facts. The same
     * trap ADR-005 avoids with {@code EXISTS} in the digest, in the other
     * direction.
     *
     * @throws IllegalArgumentException if there is no such account, which means
     *         the row was deleted inside the lifetime of a session. It renders a
     *         500 and should not: the holder of that session is signed in to
     *         nothing and belongs at the login form. Deliberately not fixed
     *         here, because the cause is a stale principal rather than anything
     *         about subscriptions -- **ADR-002's open points** carry the defect
     *         and the two candidate repairs.
     */
    @Transactional(readOnly = true)
    public SubscriptionPreferences preferencesOf(long userId) {
        AppUser user = subscriptions.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("no account " + userId));
        // Inside the transaction, deliberately: these are lazy element
        // collections and open-in-view is off, so copying them here is what
        // keeps the template from touching a detached collection (ADR-013).
        return new SubscriptionPreferences(
                Set.copyOf(user.getSubscribedCategories()),
                Set.copyOf(user.getSubscribedRegions()),
                user.isDigestEnabled());
    }

    /**
     * The effective filter for one subscriber's digest: never empty, because
     * an empty selection means every value ({@link SubscriptionFilter}).
     *
     * <p>Called once per subscriber per nightly run, by {@code digest/}. Two
     * queries, neither of which loads the user -- see
     * {@link SubscriptionRepository#findCategories}.
     *
     * <p><strong>The dependency direction is {@code digest} on
     * {@code subscription}, and only that way.</strong> This is the one place
     * "empty means everything" is decided, and the digest asking for it rather
     * than reading the tables itself is what keeps the rule from being
     * reimplemented in the query that matters most.
     */
    @Transactional(readOnly = true)
    public SubscriptionFilter effectiveFilterFor(long userId) {
        return SubscriptionFilter.of(
                subscriptions.findCategories(userId),
                subscriptions.findRegions(userId));
    }

    /**
     * Saves what page 13 posted.
     *
     * <p><strong>Replace, not merge.</strong> The form carries the complete
     * selection every time -- the page renders every category and region with
     * a checkbox -- so what arrives is the new state and not a delta. Clearing
     * and re-adding lets Hibernate do what it does for an element collection
     * anyway: delete the rows for this key, insert the new ones.
     *
     * <p><strong>Mutating the managed collections rather than assigning new
     * ones,</strong> which is why {@code AppUser} has no setter for either. A
     * fresh {@code Set} assigned over a Hibernate-managed collection throws
     * away the instance Hibernate is tracking, and on a collection with no
     * {@code orphanRemoval} the usual result is an attempt to insert rows that
     * are already there -- a violation of
     * {@code pk_subscription_category} for a save that changed nothing.
     *
     * <p>No explicit {@code save}: the entity is managed inside this
     * transaction and dirty checking writes it at flush. Calling {@code save}
     * on a managed entity is a no-op that reads as a safeguard.
     */
    @Transactional
    public void save(long userId, Set<Category> categories, Set<Region> regions, boolean digestEnabled) {
        AppUser user = subscriptions.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("no account " + userId));

        replace(user.getSubscribedCategories(), categories);
        replace(user.getSubscribedRegions(), regions);
        user.setDigestEnabled(digestEnabled);
    }

    /**
     * Unsubscribes the account named by a signed token from a digest email.
     *
     * <p><strong>This is the EU's requirement, and the one path in the
     * application that writes to {@code app_user} with no authentication at
     * all</strong> (ADR-005). What stands in for a session is the token: a
     * MAC over the user id and an expiry, bound to
     * {@link TokenPurpose#UNSUBSCRIBE} so that a verification token offered
     * here simply fails to verify.
     *
     * <p><strong>An expired token is treated as valid, which is not a
     * mistake.</strong> {@code UNSUBSCRIBE} is signed for ten years precisely
     * so this cannot happen, and if it somehow does -- a token issued before a
     * change to that lifetime -- then refusing it would mean telling someone
     * holding a genuine, this-application-signed opt-out link that they cannot
     * opt out. The signature is checked before the expiry is read
     * ({@code SignedTokenService}), so the id on an {@code Expired} token is
     * as authentic as the id on a {@code Valid} one. Honouring it is both the
     * legally safe answer and the kind one. Only {@code Invalid} is refused.
     *
     * <p><strong>Idempotent.</strong> A second click on the same link reports
     * success again, having written {@code false} over {@code false}. That is
     * the property {@code SignedTokenService} depends on for keeping no token
     * table: there is nowhere to record that a token was used, so using it
     * twice must be harmless.
     *
     * @return {@code true} if an account was unsubscribed. {@code false} for a
     *         token that does not verify, and for one that verifies against an
     *         account that no longer exists -- deliberately one return value
     *         for both, because the page they produce is the same dead link
     *         and distinguishing them on screen would tell whoever is holding
     *         a forged token which half of it was wrong.
     */
    @Transactional
    public boolean unsubscribe(String token) {
        long userId = switch (tokens.verify(token, TokenPurpose.UNSUBSCRIBE)) {
            case TokenVerification.Valid valid -> valid.userId();
            case TokenVerification.Expired expired -> expired.userId();
            case TokenVerification.Invalid ignored -> {
                // The only one of the three worth a log line, and the token
                // itself is not in it: a forgery attempt is worth knowing
                // about, and a working token in a log file is somebody else's
                // ability to unsubscribe a stranger.
                log.warn("rejected an unsubscribe token that did not verify");
                yield -1L;
            }
        };
        if (userId < 0) {
            return false;
        }

        int updated = subscriptions.setDigestEnabled(userId, false);
        if (updated == 0) {
            // Authentic token, no row: the account was deleted (ADR-015) or
            // purged before confirming (ADR-002) in the up-to-ten-years this
            // link stays signed for. Not a warning -- it is the expected end
            // state of a deleted account's old digests.
            log.info("unsubscribe token for account {} matched no row", userId);
            return false;
        }
        log.info("unsubscribed account {} from the daily digest", userId);
        return true;
    }

    /**
     * The token that goes in a digest's unsubscribe link.
     *
     * <p>Issued here rather than in {@code digest/} so that the purpose is
     * named once, by the feature that honours it: a link signed under the
     * wrong purpose is a link that fails to verify, and that failure would
     * appear a day later in somebody's mailbox rather than at this call site.
     */
    public String unsubscribeToken(long userId) {
        return tokens.issue(userId, TokenPurpose.UNSUBSCRIBE);
    }

    /**
     * {@code retainAll} then {@code addAll} rather than {@code clear} then
     * {@code addAll}, which matters for a Hibernate-managed collection: a
     * {@code clear} marks every existing row for deletion and every surviving
     * value for re-insertion, so a save that only added one category rewrites
     * all of them. Retaining first leaves the unchanged rows alone and reduces
     * the usual no-op save to no statements at all.
     */
    private static <E> void replace(Set<E> managed, Set<E> wanted) {
        managed.retainAll(wanted);
        managed.addAll(wanted);
    }
}
