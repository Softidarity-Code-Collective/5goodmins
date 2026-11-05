package com.fivegoodmins.fivegoodmins.shared.ratelimit;

/**
 * The three buckets this application has, and deliberately no mechanism for a
 * fourth: ADR-002 says rate limiting on {@code /login} and {@code /register}
 * only, and "don't build a general-purpose rate limiter".
 *
 * <p>An enum rather than a string scope, so that adding one is a visible change
 * to this file and to {@link RateLimitProperties}, which the compiler then
 * forces to grow a capacity and a window for it.
 *
 * <p><strong>Two of these refuse a request; the third refuses a send, and that
 * distinction is the whole correction of Rev 22.</strong> A bucket keyed on
 * something an attacker can name for somebody else must never be able to
 * refuse that person's request, or it is account lockout wearing a different
 * noun.
 */
enum RateLimitScope {

    /** {@code POST /login}, keyed on the client address. Refuses the request. */
    LOGIN_ATTEMPTS,

    /** {@code POST /register}, keyed on the client address. Refuses the request. */
    REGISTRATIONS_PER_ADDRESS,

    /**
     * Registration email per recipient, keyed on the address the mail would go
     * to. <strong>Refuses the send, never the request.</strong> It protects
     * somebody else's inbox rather than this server, so spending it must cost
     * its owner nothing: see {@link RateLimiter#allowRegistrationEmail}.
     */
    REGISTRATION_EMAILS_PER_RECIPIENT
}
