package com.fivegoodmins.fivegoodmins.subscription;

import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;

import java.util.Set;

/**
 * One user's subscription as page 13 reads it: a DTO, never the entity
 * (ADR-003).
 *
 * <p><strong>The selection here is the stored one, which may be empty</strong>
 * -- unlike {@link SubscriptionFilter}, where an empty selection has already
 * been replaced by the full vocabulary. The two types exist to keep those
 * apart. The page must tick exactly the boxes the user ticked, so showing it
 * the effective filter would present someone who has chosen nothing with all
 * ten categories ticked, and their next save would store what the screen
 * showed rather than what they meant. The digest needs the opposite. One
 * record for both would be one record whose meaning depends on which caller
 * made it.
 *
 * @param categories   as stored. Empty means no filter, which the digest reads
 *                     as every category (ADR-023).
 * @param regions      as stored. Empty means no filter.
 * @param digestEnabled whether the nightly email is wanted at all. The
 *                     unsubscribe link writes this same flag without a login.
 */
public record SubscriptionPreferences(Set<Category> categories,
                                      Set<Region> regions,
                                      boolean digestEnabled) {
}
