package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.action.Category;

/**
 * One {@code (action_id, category)} row from {@code action_category}, for the
 * badge query.
 *
 * <p>A top-level record rather than one nested in {@link DigestQueries},
 * because a JPQL constructor expression names the class by its fully qualified
 * name and a nested class's real name contains a {@code $} -- a dependency on
 * how Hibernate parses that string, for no gain.
 *
 * <p>Package-private, and it never reaches a template: {@link DigestQueries}
 * groups these into the sets on {@link DigestAction} and this type stops
 * existing at that line.
 */
record DigestCategoryTag(Long actionId, Category category) {
}
