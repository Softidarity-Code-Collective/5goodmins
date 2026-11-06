package com.fivegoodmins.fivegoodmins.subscription;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Page 9 of the frontend brief: the unsubscribe link out of a digest email,
 * working with no login.
 *
 * <p><strong>Two requests, not one, and this is a correction to
 * ADR-005.</strong> That ADR specifies "one-click, no auth", and the no-auth
 * half is right and is a legal requirement. One-click is wrong, for a reason
 * ADR-005 itself establishes elsewhere: mail clients and security scanners
 * <em>prefetch</em> links in email -- it is why the welcome email has to be
 * published from the one branch that writes {@code email_verified_at}, rather
 * than from every request that renders the confirmation page. A GET that
 * unsubscribed on arrival would therefore be performed by Gmail's prefetcher,
 * by Outlook's Safe Links scanner, or by whatever opens a link to generate a
 * preview, and the reader would discover they had been unsubscribed by not
 * receiving tomorrow's digest. Which is, exactly, the outcome an unsubscribe
 * mechanism exists to make deliberate.
 *
 * <p>So the GET renders a confirmation carrying the token in a hidden field,
 * and the POST performs the unsubscribe. A prefetched GET renders a page and
 * changes nothing. The brief's description of page 9 -- "one page: confirm,
 * done" -- is this, and it is one click for the reader either way.
 *
 * <p><strong>CSRF applies and is wanted, even with no session to protect.</strong>
 * Spring Security issues a token to an anonymous request, the GET page carries
 * it, and the POST is checked like any other. There is no account takeover to
 * prevent here; what it stops is a page on another site silently POSTing
 * somebody's unsubscribe link for them, which is the same prefetch problem
 * with a human behind it.
 *
 * <p><strong>The one standard thing this does not yet do</strong> is the
 * {@code List-Unsubscribe} / {@code List-Unsubscribe-Post} header pair
 * (RFC 8058), which is what puts the mail client's own "unsubscribe" button
 * next to the sender's name, and which Gmail and Yahoo require of bulk
 * senders. It is not a redesign from here: the POST endpoint that RFC needs is
 * the one below, and what remains is two headers on the digest and a CSRF
 * exemption for a provider that posts without a token. Recorded as an open
 * point in ADR-005 rather than half-built.
 *
 * <p>No {@code @AuthenticationPrincipal} anywhere in this class, and no
 * account id in any URL: the signed token is the only input, and
 * {@link SubscriptionService#unsubscribe} is what turns it into a row.
 */
@Controller
class UnsubscribeController {

    private final SubscriptionService subscriptions;

    UnsubscribeController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    /**
     * The confirmation page. Renders for any token, including one that will
     * turn out not to verify.
     *
     * <p><strong>Deliberately not validating the token here.</strong> Checking
     * it on the GET and showing a dead-link page early would be a small
     * improvement in one case and a leak in another: it would turn this page
     * into an oracle that says whether a given token is genuine, answerable
     * without ever posting anything. The POST is where the answer is given,
     * once, to somebody who asked for it.
     *
     * <p>The token travels in the query string, which is where an emailed link
     * can put it, and then in a hidden field. Not in a path segment: a token
     * containing Base64url characters is safer as a parameter than as a path,
     * and the query form is what every digest already carries.
     */
    @GetMapping(SubscriptionPaths.UNSUBSCRIBE)
    String confirm(@RequestParam(name = SubscriptionPaths.TOKEN_PARAMETER, required = false) String token,
                   Model model) {
        // required = false, so a bare /unsubscribe with no parameter renders
        // the same page rather than a 400. It is a URL a curious reader will
        // type, and the POST refuses a null token like any other.
        model.addAttribute("token", token);
        return "subscription/unsubscribe";
    }

    /**
     * Performs the unsubscribe and renders the outcome.
     *
     * <p><strong>Renders rather than redirects,</strong> which is the opposite
     * of the preferences page and for a specific reason: there is nowhere to
     * redirect to. A signed-out reader has no settings page to land on, and
     * carrying the token through a redirect to show the result would put it in
     * a second URL -- in the browser history, and in any referrer. The POST
     * answers directly and the token stays in a request body.
     *
     * <p>Re-posting is harmless: the write is idempotent
     * ({@link SubscriptionService#unsubscribe}), so a refresh and its
     * resubmission dialogue produce the same confirmation.
     *
     * @param token the signed {@code UNSUBSCRIBE} token, from the hidden field
     */
    @PostMapping(SubscriptionPaths.UNSUBSCRIBE)
    String unsubscribe(@RequestParam(name = SubscriptionPaths.TOKEN_PARAMETER, required = false) String token,
                       Model model) {
        model.addAttribute("unsubscribed", subscriptions.unsubscribe(token));
        return "subscription/unsubscribe-result";
    }
}
