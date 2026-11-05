package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.action.ActionSummary;

import java.util.List;
import java.util.Locale;

/**
 * What {@link RegistrationService} asks to be emailed once its transaction has
 * committed (ADR-005) -- two events from {@code register} and one from
 * {@code verifyEmail}.
 *
 * <p><strong>Both registration outcomes publish one of these, and that is the
 * requirement rather than a convention.</strong> {@code register} renders the
 * same page for a free address and for an address that already has a confirmed
 * account, and pays a BCrypt hash on both paths so the two cannot be told apart
 * with a stopwatch. An email sent on one path and not the other would undo
 * that: an HTTP call to a mail provider is 100-500ms, several times the gap
 * hashing on both paths closes. Two events through one listener keeps the two
 * paths the same shape as well as the same page.
 *
 * <p><strong>It carries values, not the entity.</strong> By the time a
 * {@code @TransactionalEventListener(AFTER_COMMIT)} runs, the transaction is
 * finished and the persistence context is gone, so an {@code AppUser} in here
 * would be a detached object whose next lazy access throws on a pool thread --
 * and entities do not leave the service layer anyway (ADR-013).
 *
 * <p>Sealed, so the listener's {@code switch} is exhaustive without a default
 * branch: a fourth registration email would be a compile error there rather
 * than an email that is quietly never sent. {@code Welcome} was added that way
 * -- the compiler named the one place it had to be handled.
 */
sealed interface RegistrationEmailEvent {

    /**
     * {@code app_user.id}. Not for the email -- for the log line that says
     * which account a send failed for, since the address itself is the one
     * thing in here worth keeping out of a log on a site whose users are
     * activists.
     */
    long userId();

    /** The recipient, normalized and read back from the row that was written. */
    String email();

    /** Shown in the greeting. User-supplied, so it appears only in a body. */
    String displayName();

    /**
     * The recipient's stored interface language, never the server's default and
     * never the locale of the request that triggered this -- the listener runs
     * on a pool thread that inherits neither.
     */
    Locale locale();

    /**
     * An address that is now a new, unverified row: send the link that confirms
     * it.
     *
     * @param token the {@code EMAIL_VERIFICATION} token. Anyone holding it can
     *              confirm this account, so it belongs in the link and nowhere
     *              else -- not in a log line, not in an exception message, not
     *              in the arguments the async exception handler prints.
     */
    record Verification(long userId, String email, String displayName, Locale locale, String token)
            implements RegistrationEmailEvent { }

    /**
     * An address that already belongs to a confirmed account: tell its owner
     * that someone tried to register it, and that nothing changed.
     *
     * <p>This is the only thing that answers the person who genuinely forgot
     * they have an account -- the form will not tell them, because telling them
     * would tell a stranger too. The mailbox is the one place it is safe to
     * say, since only its owner reads it.
     *
     * <p>ADR-005 describes this mail as "you already have an account, reset
     * your password instead". It cannot say the second half yet: password reset
     * is v1.1 (frontend brief §10), so the email offers the login page and
     * stops there. It is the one place the missing reset is visible to a user,
     * and the copy changes when the feature lands.
     *
     * <p>No token, because there is nothing to authorise: it links to a page
     * anyone can reach.
     */
    record ExistingAccount(long userId, String email, String displayName, Locale locale)
            implements RegistrationEmailEvent { }

    /**
     * An address that has just been confirmed: welcome its owner and show them
     * what is on the site (ADR-005).
     *
     * <p><strong>Published from the transition, not from the request.</strong>
     * {@code verifyEmail} is reached by a link that mail clients prefetch and
     * that users click twice, so "confirmed" is a page three different inputs
     * can produce. Only one of them actually writes {@code email_verified_at},
     * and that write is where this event is published -- so the welcome is sent
     * exactly once with no column to record that it was. "Has the welcome been
     * sent?" is answered by {@code email_verified_at IS NOT NULL}, which is
     * ADR-005's reason for not adding one.
     *
     * @param actions the five newest active actions, loaded inside the
     *                confirming transaction and carried here as projections for
     *                the same reason this event carries no entity: by the time
     *                the listener runs there is no persistence context to load
     *                them from. Unpersonalized, because subscriptions are
     *                usually not set at this moment (ADR-005) -- and possibly
     *                empty, on a site with nothing published yet, which the
     *                template has to say rather than render as a blank gap.
     */
    record Welcome(long userId,
                   String email,
                   String displayName,
                   Locale locale,
                   List<ActionSummary> actions)
            implements RegistrationEmailEvent { }
}
