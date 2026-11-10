package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.action.ActionSummary;
import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import com.fivegoodmins.fivegoodmins.subscription.SubscriptionFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the three queries behind one subscriber's digest into a list of
 * {@link DigestAction}.
 *
 * <p>The grouping step, separated from {@link DigestService} because the two
 * have different reasons to change: this one is about not duplicating an
 * action that carries two regions, and that one is about when to send and when
 * to move a watermark. Keeping them apart also means the assembly can be
 * tested against a real database with no mail, no clock and no lock in the way
 * -- which is what {@code DigestQueriesTest} does.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} around all three
 * queries,</strong> so the badge queries see the same snapshot as the query
 * that chose the ids. Without it an action approved between the first and
 * second query would arrive with no badges at all and render an entry with
 * empty tag lists -- rare, nightly, and exactly the kind of defect nobody can
 * reproduce.
 *
 * <p><strong>{@link #digestFor} is {@code public} on a package-private class,
 * which is not an oversight.</strong> It was package-private, and that made the
 * paragraph above a claim resting on undocumented behaviour: Spring's
 * proxy-based AOP advises a non-public method only under class-based (CGLIB)
 * proxies, which is Boot's default and does work, but it is not what the
 * reference documentation promises and it reverts silently if anybody sets
 * {@code spring.aop.proxy-target-class=false}. A transaction boundary that
 * exists by accident of a default is the same species of bug as the missing
 * {@code @Transactional} on {@code advanceWatermark} that this feature already
 * shipped once -- so it is {@code public}, which costs no encapsulation at all
 * because the class itself is package-private and nothing outside
 * {@code digest/} can name the type.
 *
 * <p>And the claim is now tested rather than asserted:
 * {@code DigestQueriesTransactionTest} reads
 * {@code TransactionSynchronizationManager.getCurrentTransactionName()} from
 * inside each of the three repository calls and requires all three to name
 * <em>this</em> method, which is what "the same snapshot" means operationally.
 */
@Service
class DigestQueries {

    private final DigestActionRepository actions;

    DigestQueries(DigestActionRepository actions) {
        this.actions = actions;
    }

    /**
     * What this subscriber has not been told about yet, with its badges
     * attached.
     *
     * @param watermark this subscriber's {@code last_digest_sent_at}
     * @param cutoff    {@code now} minus the lag window
     * @param now       ADR-019: passed in, never read from a clock here
     * @param filter    the effective subscription -- never empty, because
     *                  {@link SubscriptionFilter} has already replaced an empty
     *                  selection with the full vocabulary
     * @return in publication order, oldest first. Empty when there is nothing
     *         new, which is the common case on any given night and the signal
     *         {@link DigestService} uses to send nothing at all.
     */
    @Transactional(readOnly = true)
    public List<DigestAction> digestFor(Instant watermark, Instant cutoff, Instant now,
                                        SubscriptionFilter filter) {
        List<DigestActionRow> rows = actions.findMatching(
                watermark, cutoff, now,
                filter.categories(), filter.regions(),
                // From action/, not redeclared here. The invariant that number
                // carries -- the SQL read bound stays comfortably above every
                // display length -- is the same invariant in this email, and
                // two constants would be two chances to break it.
                ActionSummary.EXCERPT_CHARS);

        if (rows.isEmpty()) {
            // Before the badge queries, not after: an empty IN list is the
            // thing this codebase does not depend on, and there is no email to
            // build anyway.
            return List.of();
        }

        List<Long> ids = rows.stream().map(DigestActionRow::id).toList();
        Map<Long, Set<Category>> categories = groupCategories(actions.findCategories(ids));
        Map<Long, Set<Region>> regions = groupRegions(actions.findRegions(ids));

        List<DigestAction> digest = new ArrayList<>(rows.size());
        for (DigestActionRow row : rows) {
            digest.add(new DigestAction(
                    row.id(), row.title(), row.actionType(), row.excerpt(),
                    // Empty rather than null for an action with no tag rows,
                    // which the schema does not permit and a template should
                    // not crash on. getOrDefault, so a missing key is an empty
                    // badge list and not a NullPointerException on the mail
                    // pool at seven in the morning.
                    categories.getOrDefault(row.id(), Set.of()),
                    regions.getOrDefault(row.id(), Set.of())));
        }
        return digest;
    }

    /*
     * Two near-identical grouping methods rather than one generic one, which
     * was tried and read worse: the pairs are two different record types with
     * two differently named accessors, so a shared version needs two method
     * references passed in and the call site then says less than the body.
     *
     * LinkedHashMap and LinkedHashSet, so the badges on an action render in the
     * order the database returned them rather than in a hash order that varies
     * between runs. Nothing depends on it; an email whose badges reshuffle
     * between two renderings of the same action is just harder to diff while
     * designing it.
     */

    private static Map<Long, Set<Category>> groupCategories(List<DigestCategoryTag> tags) {
        Map<Long, Set<Category>> grouped = new LinkedHashMap<>();
        for (DigestCategoryTag tag : tags) {
            grouped.computeIfAbsent(tag.actionId(), id -> new LinkedHashSet<>()).add(tag.category());
        }
        return grouped;
    }

    private static Map<Long, Set<Region>> groupRegions(List<DigestRegionTag> tags) {
        Map<Long, Set<Region>> grouped = new LinkedHashMap<>();
        for (DigestRegionTag tag : tags) {
            grouped.computeIfAbsent(tag.actionId(), id -> new LinkedHashSet<>()).add(tag.region());
        }
        return grouped;
    }
}
