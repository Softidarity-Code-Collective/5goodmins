package com.fivegoodmins.fivegoodmins.digest;

/**
 * What one nightly run did, for the log line and for a test to assert on.
 *
 * <p>Returned rather than only logged, because the alternative is a test that
 * proves the watermark logic by reading log output. The job itself ignores it
 * ({@code DigestJob} is one call), which is the right shape: the counts are
 * evidence, not a control signal.
 *
 * <p>Four numbers rather than two, because "nothing new" and "failed" are
 * opposite things that both produce no email, and a single
 * "emails not sent" count would hide the difference. {@code nothingNew} is the
 * ordinary state of most subscribers on most nights; {@code failed} climbing is
 * the one number worth an alert.
 *
 * @param subscribers how many accounts were due a digest at all: verified,
 *                    enabled and subscribed.
 * @param sent        how many emails went out, each followed by a watermark
 *                    advance.
 * @param nothingNew  how many had no matching actions since their watermark.
 *                    No email and, deliberately, no watermark advance either
 *                    (ADR-005).
 * @param failed      how many threw. Their watermarks are untouched, so
 *                    tomorrow's run carries today's actions too -- which is
 *                    the property that makes a provider outage cost a delay
 *                    rather than a gap.
 */
public record DigestRun(int subscribers, int sent, int nothingNew, int failed) {
}
