package com.fivegoodmins.fivegoodmins.subscription;

import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;

import java.util.EnumSet;
import java.util.Set;

/**
 * What one subscriber's digest is allowed to contain: the categories and
 * regions an action must match (ADR-022).
 *
 * <p><strong>These sets are always non-empty, and that is the whole point of
 * this type.</strong> A user who has never opened the preferences page has no
 * rows in {@code subscription_category}, and a user who enables the digest
 * without ticking anything has none either. Both mean "everything", not
 * "nothing" -- so {@link #of} replaces an empty selection with the complete
 * vocabulary, and every consumer downstream can then do one thing with no
 * branch in it.
 *
 * <p><strong>Why empty means everything and not nothing.</strong> The digest
 * toggle is the opt-in; the categories and regions are a narrowing filter on
 * top of it. Read the other way round, the feature would be invisible by
 * default: a registrant who never visits a settings page would be subscribed,
 * per {@code app_user.digest_enabled}, to a digest that silently matched
 * nothing forever, and the welcome email's promise of a daily digest would be
 * false for the majority of accounts. The failure mode of this direction is a
 * digest that is broader than someone wanted, which the preferences page and
 * the unsubscribe link both fix; the failure mode of the other is silence
 * nobody can diagnose.
 *
 * <p><strong>Substituting the full set, rather than branching in the
 * query.</strong> The digest's matching query takes these as bound {@code IN}
 * lists, and the alternative shapes are both worse. A query with
 * {@code (:unfiltered = true or exists (...))} carries a flag per facet and
 * two code paths through the one piece of SQL that has to be right. Passing an
 * empty collection is worse still: Hibernate's handling of an empty {@code IN}
 * list has changed across versions and is not something to depend on. Ten
 * categories and thirty regions are small enough that the full list costs
 * nothing to send, and the rule becomes one substitution in one place.
 *
 * @param categories never empty; the complete {@link Category} set when the
 *                   subscriber chose none.
 * @param regions    never empty; the complete {@link Region} set when the
 *                   subscriber chose none.
 */
public record SubscriptionFilter(Set<Category> categories, Set<Region> regions) {

    /**
     * @param categories as stored, possibly empty
     * @param regions    as stored, possibly empty
     */
    static SubscriptionFilter of(Set<Category> categories, Set<Region> regions) {
        return new SubscriptionFilter(
                orEverything(categories, Category.class),
                orEverything(regions, Region.class));
    }

    /**
     * {@code EnumSet.allOf} rather than a cached static, because
     * {@link EnumSet} is mutable and a shared instance handed to callers is a
     * constant anybody can empty. The allocation is thirty bits once per
     * subscriber per night.
     *
     * <p>{@code copyOf} on the non-empty path for the same reason in reverse:
     * the caller's set came from a Hibernate-managed collection, and handing
     * that out past the transaction is how a detached collection ends up being
     * iterated on a pool thread.
     */
    private static <E extends Enum<E>> Set<E> orEverything(Set<E> selected, Class<E> type) {
        return selected == null || selected.isEmpty()
                ? EnumSet.allOf(type)
                : EnumSet.copyOf(selected);
    }
}
