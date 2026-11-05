package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.action.ActionPaths;
import com.fivegoodmins.fivegoodmins.shared.AuthPaths;
import com.fivegoodmins.fivegoodmins.shared.email.EmailComposer;
import com.fivegoodmins.fivegoodmins.shared.email.EmailConfig;
import com.fivegoodmins.fivegoodmins.shared.email.EmailMessage;
import com.fivegoodmins.fivegoodmins.shared.email.EmailProperties;
import com.fivegoodmins.fivegoodmins.shared.email.EmailSender;
import com.fivegoodmins.fivegoodmins.shared.email.EmailTemplate;
import com.fivegoodmins.fivegoodmins.shared.ratelimit.RateLimiter;
import com.fivegoodmins.fivegoodmins.shared.token.TokenPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Sends the two registration emails, after the transaction that caused them has
 * committed and off the thread that is answering the user (ADR-005).
 *
 * <p>Two mechanisms, two different bugs, and keeping them apart is the point:
 *
 * <ul>
 *   <li><strong>{@code AFTER_COMMIT} is correctness.</strong> A send inside the
 *       transaction goes out even when the transaction later rolls back, so
 *       someone receives a verification link for an account that does not
 *       exist -- and because the token signs a user id that was never
 *       committed, that link resolves to nothing for as long as it lives.</li>
 *   <li><strong>The executor is the timing oracle.</strong> An after-commit
 *       listener runs on the publishing thread, so without one the HTTP
 *       response waits for the mail provider: 100-500ms on the path that
 *       registers and none on the path that refuses, which tells a stopwatch
 *       what the identical pages will not.</li>
 * </ul>
 *
 * <p><strong>The submit is explicit rather than {@code @Async}, and that is a
 * measured decision.</strong> ADR-005 specified the annotation, and it works --
 * until the queue fills. Rejection happens at submit time, which with
 * {@code @Async} is inside Spring's transaction synchronization: the
 * {@code TaskRejectedException} propagates out of the proxy, past this class
 * entirely, and lands in {@code TransactionSynchronizationUtils}, which logs
 * <em>"TransactionSynchronization.afterCompletion threw exception"</em> and the
 * stack trace. No account id, no address, nothing saying it was an email --
 * because at that point the event is no longer in scope. Neither the catch
 * below nor an {@code AsyncUncaughtExceptionHandler} sees it; both cover
 * failures <em>inside</em> the task. Measured by saturating the queue, not
 * reasoned about. Submitting by hand is three lines and puts the rejection
 * where the event still is.
 *
 * <p>What the explicit submit costs: the request thread does the submitting, a
 * few microseconds, on both registration paths equally. What it buys: the one
 * failure this design is built to produce is attributable.
 *
 * <p><strong>A failure rolls nothing back</strong>, which is right -- an
 * account should not be undone because a mail provider was briefly down -- and
 * is therefore only as good as its logging. Both failure modes are logged with
 * the user id: the send that threw, and the send that was never attempted.
 *
 * <p><strong>The user's half of that has to exist too.</strong> With no resend
 * form (ADR-002), their recovery is to register again -- which replaces the
 * row and issues a fresh link, so it really is a resend -- and the rate limit
 * on {@code /register} is what keeps it from being a way to flood a mailbox.
 * A recovery nobody is told about is not one, so the "check your inbox" page
 * carries it (frontend brief, page 8; the strings are
 * {@code verification.notice.spam} and {@code verification.notice.resend}).
 */
@Component
class RegistrationEmailListener {

    private static final Logger log = LoggerFactory.getLogger(RegistrationEmailListener.class);

    /*
     * The paths are AuthPaths.VERIFY_EMAIL and AuthPaths.LOGIN, shared with the
     * controllers that serve them. They are built into absolute URLs here, from
     * EmailProperties.baseUrl(), because an after-commit listener has no request
     * to derive a host from -- and the host it could derive would be Tomcat's
     * rather than the public one.
     */

    private static final EmailTemplate VERIFICATION =
            new EmailTemplate("email/verification", "email.verification.subject");

    private static final EmailTemplate EXISTING_ACCOUNT =
            new EmailTemplate("email/existing-account", "email.existingAccount.subject");

    private static final EmailTemplate WELCOME =
            new EmailTemplate("email/welcome", "email.welcome.subject");

    private final EmailComposer composer;
    private final EmailSender sender;
    private final EmailProperties properties;
    private final AsyncTaskExecutor emailExecutor;
    private final RateLimiter rateLimiter;

    RegistrationEmailListener(EmailComposer composer,
                              EmailSender sender,
                              EmailProperties properties,
                              @Qualifier(EmailConfig.EMAIL_EXECUTOR) AsyncTaskExecutor emailExecutor,
                              RateLimiter rateLimiter) {
        this.composer = composer;
        this.sender = sender;
        this.properties = properties;
        // Qualified by name: Boot's applicationTaskExecutor is also an
        // AsyncTaskExecutor, so by type this is ambiguous -- and the wrong one
        // is the unbounded pool that serves the web layer.
        this.emailExecutor = emailExecutor;
        this.rateLimiter = rateLimiter;
    }

    /**
     * One listener for both events, so that neither registration outcome can
     * acquire a send path -- or a send latency -- the other does not have.
     *
     * <p>Runs on the thread that committed, and does one thing on it: hand the
     * work to the mail pool. Rendering and sending happen on {@code email-1}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegistrationEmail(RegistrationEmailEvent event) {
        try {
            emailExecutor.execute(() -> sendNow(event));
        } catch (TaskRejectedException queueFull) {
            // The mail pool is saturated: fifty sends are already waiting, so
            // the provider has been slow or down for a while. This email is
            // dropped rather than queued, which is what the bounded queue is
            // for -- and the point of catching it here rather than letting it
            // escape is that this line names the account. Escaping, it reaches
            // TransactionSynchronizationUtils and becomes an anonymous stack
            // trace.
            //
            // Not rethrown: the registration committed and is correct. Failing
            // the response now would tell the user their account does not
            // exist when it does.
            log.error("mail queue full, no {} email sent for account {}",
                    event.getClass().getSimpleName(), event.userId(), queueFull);
        }
    }

    /**
     * On the mail pool. Catches everything, because this is the end of the line:
     * there is no caller left to handle anything, and an exception escaping here
     * goes to the executor's own handler, which logs at a level and in a format
     * nobody chose for the one failure whose only symptom is an email that
     * never arrives.
     */
    private void sendNow(RegistrationEmailEvent event) {
        if (!mayMail(event)) {
            // INFO, not WARN, and the comment used to say "not logged as a
            // failure" above a line that logged it above the success case.
            // This is routine: it is what the cap doing its job looks like, it
            // is unbounded under an attack because every request past the third
            // produces one, and the signal an operator actually wants for that
            // is the per-address refusal in RateLimitFilter, which is a WARN
            // and names the client. The id and never the address, for the same
            // reason as every other log line here.
            log.info("suppressed the {} email for account {}: this recipient's daily cap is spent",
                    event.getClass().getSimpleName(), event.userId());
            return;
        }
        try {
            sender.send(compose(event));
            log.info("sent the {} email for account {}",
                    event.getClass().getSimpleName(), event.userId());
        } catch (RuntimeException failed) {
            // The allowance goes back. It counts mail that reached an inbox,
            // not attempts to hand it to a provider -- otherwise a Brevo
            // outage plus three retries leaves the address capped for a day
            // having received nothing, which is the limiter punishing the
            // person it exists to protect. Same rule as ADR-005's digest
            // watermark, which moves only after a successful send.
            refundMail(event);
            // The id, never the event: a Verification carries the token, and a
            // token in a log file is a verification somebody else can perform.
            log.error("failed to send the registration email for account {}", event.userId(), failed);
        }
    }

    /**
     * ADR-002's per-recipient cap on registration email, applied here rather
     * than in {@code RateLimitFilter} where it started.
     *
     * <p><strong>Why it moved.</strong> Keyed on the submitted address, a cap
     * that refuses the <em>request</em> lets three POSTs naming
     * {@code victim@example.com} lock the real owner of that address out of
     * registering for a day -- the denial-of-service weapon ADR-002 rejects
     * account lockout for, rebuilt on the registration form, and reachable
     * without a session or a CSRF token because the filter sits in front of
     * {@code CsrfFilter}. The cap's own stated job is to protect a stranger's
     * inbox rather than this server, so the send is where it belongs: the
     * mailbox is still protected, the owner is refused nothing, and
     * {@code /register} keeps the one neutral response every address must get.
     *
     * <p><strong>What it costs, stated plainly.</strong> A suppressed send is
     * an email that never arrives, and the recovery on page 8 of the frontend
     * brief -- register again -- will not produce one either until the window
     * passes. So an attacker can still deny a targeted address its
     * <em>email</em> for a day; what they can no longer do is deny it the
     * account. That is a strictly smaller weapon and it is the smallest one
     * available, because no cap on mail to an address can both bound the mail
     * and guarantee the next message gets through.
     *
     * <p><strong>The welcome email is exempt</strong>, because it cannot be
     * used as a cannon: reaching it requires a valid signed token for that
     * account, which only the mailbox's reader has. Capping it would mean a
     * user who confirms after an attacker has spent the allowance is welcomed
     * by silence.
     *
     * <p>The address is normalized again here rather than trusted. Both
     * registration events carry it read back from the row, so it is already
     * canonical -- but a cap bypassed by a capital letter is the kind of
     * defect that survives review, and this costs one call.
     */
    private boolean mayMail(RegistrationEmailEvent event) {
        if (isCapped(event)) {
            return rateLimiter.allowRegistrationEmail(AppUser.normalizeEmail(event.email()));
        }
        return true;
    }

    /**
     * Puts back what {@link #mayMail} spent. Mirrors it exactly, including the
     * exemption: refunding an event that was never charged would hand the
     * recipient a free token every time a welcome email failed.
     */
    private void refundMail(RegistrationEmailEvent event) {
        if (isCapped(event)) {
            rateLimiter.refundRegistrationEmail(AppUser.normalizeEmail(event.email()));
        }
    }

    /**
     * Which events the cap applies to: the two an unauthenticated POST to
     * {@code /register} can aim at somebody else's inbox. The welcome email is
     * not one of them — see {@link #mayMail}.
     */
    private static boolean isCapped(RegistrationEmailEvent event) {
        return !(event instanceof RegistrationEmailEvent.Welcome);
    }

    private EmailMessage compose(RegistrationEmailEvent event) {
        return switch (event) {
            case RegistrationEmailEvent.Verification verification -> composer.compose(
                    verification.email(),
                    verification.locale(),
                    VERIFICATION,
                    Map.of("displayName", verification.displayName(),
                            "verificationUrl", verificationUrl(verification.token()),
                            // From the enum rather than written again as a
                            // literal in two message bundles: the seven days in
                            // the email is the token's lifetime, and the day
                            // those disagree the email is the one that is wrong.
                            "expiryDays", TokenPurpose.EMAIL_VERIFICATION.ttl().toDays()));
            case RegistrationEmailEvent.ExistingAccount existing -> composer.compose(
                    existing.email(),
                    existing.locale(),
                    EXISTING_ACCOUNT,
                    Map.of("displayName", existing.displayName(),
                            "loginUrl", url(AuthPaths.LOGIN)));
            case RegistrationEmailEvent.Welcome welcome -> composer.compose(
                    welcome.email(),
                    welcome.locale(),
                    WELCOME,
                    // The projections go straight to the template. There was a
                    // view record here that added an absolute link per action,
                    // pointing at a detail page that is page 2 of the frontend
                    // brief and does not exist -- five dead links in the first
                    // email a confirmed user receives. The actions are listed
                    // as text now and the one link is the call to action.
                    Map.of("displayName", welcome.displayName(),
                            "actions", welcome.actions(),
                            "actionsUrl", url(ActionPaths.LIST)));
        };
    }


    /**
     * {@code UriComponentsBuilder} rather than string concatenation, so the
     * token is percent-encoded by something that knows the rules. Today's
     * tokens are digits, dots and Base64url, all of which survive a query
     * string untouched -- but that is a property of the current token format,
     * and the day it changes is not the day to discover this was hand-rolled.
     */
    private String verificationUrl(String token) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path(AuthPaths.VERIFY_EMAIL)
                .queryParam(AuthPaths.TOKEN_PARAMETER, token)
                .build()
                .encode()
                .toUriString();
    }

    private String url(String path) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl()).path(path).build().encode().toUriString();
    }
}
