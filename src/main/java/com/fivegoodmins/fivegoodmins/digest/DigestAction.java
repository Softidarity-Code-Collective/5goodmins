package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.action.ActionType;
import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;

import java.util.Set;

/**
 * One action as a digest email lists it (frontend brief §4, email 3): a type
 * label, a title, its category and region badges, and one line of description.
 *
 * <p><strong>Richer than {@code action/ActionSummary}, which is why it is a
 * separate record rather than a reuse.</strong> ADR-005 says plainly that the
 * welcome email does without badges because they live in two element
 * collections, and that "the digest and page 1 do, and they can pay for a
 * richer projection". This is that projection, and the price is paid in
 * {@link DigestActionRepository}: three queries per subscriber rather than one,
 * assembled in Java, so that no action is ever duplicated by a join.
 *
 * <p><strong>No link, and that is the same decision as the welcome email's
 * rather than a new one.</strong> The frontend brief describes each digest
 * entry as carrying a link, and the destination would be the action detail
 * page -- page 2 of the brief, which does not exist. {@code ActionPaths} has
 * no {@code detail(id)} for precisely this reason: an earlier version had one,
 * the welcome email built five links from it, and five dead links in a
 * delivered email was the result. A digest is worse than the welcome email on
 * this axis, not better: it goes out every day, to everybody, forever. So the
 * actions are listed as text with one call to action pointing at the listing,
 * exactly as the welcome email does, and the per-action links land in both
 * templates together with page 2. That is an open point in ADR-005 and the
 * biggest thing still owed to this email.
 *
 * <p><strong>{@code id} is carried anyway</strong>, even with nothing linking
 * to it: it is what {@link DigestActionRepository} groups the badge rows by,
 * and it is what a log line or a test names an action by.
 *
 * @param id         {@code action.id}.
 * @param title      shown whole. User-supplied, so it is escaped by the
 *                   template and never reaches a subject line.
 * @param actionType labelled from the bundle under {@code action.type.<constant>}
 *                   (ADR-017), never from a string in Java.
 * @param excerpt    the first {@code ActionSummary.EXCERPT_CHARS} characters of
 *                   the description, cut in SQL. The constant is reused from
 *                   {@code action/} rather than redeclared, because the
 *                   invariant it carries -- the read bound stays comfortably
 *                   above every display length -- is the same invariant here,
 *                   and two numbers would be two chances to break it. ADR-005
 *                   names the digest as the reason that cut exists at all:
 *                   reading 5,000 characters to display 140 is affordable once
 *                   per account verification and not once per subscriber per
 *                   day.
 * @param categories one to three, from {@code action_category}. Never empty --
 *                   an action must be tagged to exist -- but the record does
 *                   not enforce that, because a projection asserting an
 *                   invariant it cannot see the whole of is a projection that
 *                   throws during an email.
 * @param regions    one to three, from {@code action_region}.
 */
public record DigestAction(Long id,
                           String title,
                           ActionType actionType,
                           String excerpt,
                           Set<Category> categories,
                           Set<Region> regions) {
}
