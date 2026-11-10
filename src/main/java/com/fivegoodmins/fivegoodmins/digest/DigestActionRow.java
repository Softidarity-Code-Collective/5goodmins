package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.action.ActionType;

/**
 * One row of the digest's matching query, before its badges are attached.
 *
 * <p><strong>A second record alongside {@link DigestAction}, which is not
 * duplication for its own sake.</strong> The badges cannot come back from that
 * query: they live in two element collections, and joining them in is exactly
 * the duplication ADR-005 uses {@code EXISTS} to avoid -- an action tagged
 * {@code BURGAS, NATIONAL} would arrive twice. So the query returns the
 * single-valued columns, two more queries fetch the tags for every id at once,
 * and {@link DigestQueries} assembles the pair into the {@link DigestAction}
 * the template sees.
 *
 * <p>The alternative was a JPQL constructor expression passing {@code null} for
 * the two sets and filling them in afterwards, which needs a mutable
 * projection and gives the template a type whose fields are sometimes a lie.
 *
 * <p>Package-private: nothing outside this package has a use for a half-built
 * action, and {@link DigestAction} is the one shape that leaves.
 *
 * @param excerpt cut to {@code ActionSummary.EXCERPT_CHARS} in SQL, bound as a
 *                parameter so the number lives in one place.
 */
record DigestActionRow(Long id, String title, ActionType actionType, String excerpt) {
}
