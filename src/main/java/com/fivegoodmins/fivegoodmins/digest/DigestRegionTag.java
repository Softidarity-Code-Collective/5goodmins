package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.action.Region;

/**
 * One {@code (action_id, region)} row from {@code action_region}, for the badge
 * query. The region half of {@link DigestCategoryTag}, and separate for the
 * only reason two near-identical records ever are: the second component is a
 * different enum, and JPQL has no generics.
 */
record DigestRegionTag(Long actionId, Region region) {
}
