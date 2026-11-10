package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.action.Action;
import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * The digest's matching query (ADR-005) and the two that fetch its badges.
 *
 * <p>Here rather than on {@code ActionRepository} because this is the digest's
 * read model and nothing else's; the cost -- {@code digest/} knowing the shape
 * of {@code action}'s data -- and the containment are argued in ADR-023.
 * {@code Repository} rather than {@code JpaRepository} is that containment:
 * three reads, no writes.
 *
 * <p><strong>Three queries, not one join.</strong> A join against the tag
 * tables returns an action once per matching tag, so an action tagged
 * {@code BURGAS, NATIONAL} would arrive twice for a subscriber who follows
 * both. The badges are fetched per facet for all of that subscriber's actions
 * at once, and grouped by {@link DigestQueries}, which also holds the
 * transaction the three share.
 */
interface DigestActionRepository extends Repository<Action, Long> {

    /**
     * Everything one subscriber has not been told about yet and could still act
     * on, oldest first.
     *
     * <p>The clauses, and what each is for:
     *
     * <ul>
     *   <li>{@code publishedAt > :watermark} -- strictly greater, so the action
     *       that moved the watermark last night is not sent again tonight. An
     *       off-by-one here is a duplicate in every subscriber's inbox.</li>
     *   <li><strong>{@code publishedAt <= :cutoff} -- the lag window, and the
     *       clause most likely to look redundant to somebody tidying this
     *       up.</strong> {@code now()} is transaction start time, so a publish
     *       beginning at 06:59:59 is invisible to a job starting at 07:00:00
     *       which would then move the watermark past it: the row commits with
     *       an earlier {@code published_at} and is never mailed to anybody,
     *       ever. Excluding the last few minutes -- and advancing the watermark
     *       only to this same cutoff -- is what closes that.</li>
     *   <li>{@code endsAt > :now} -- never mail an expired action. Derived per
     *       query; there is no {@code EXPIRED} status.</li>
     *   <li>two {@code exists} subqueries, one per facet, which is what stops a
     *       multiply-tagged action appearing twice.</li>
     * </ul>
     *
     * <p>Keyed on {@code published_at} and never {@code created_at} or
     * {@code updated_at} (ADR-005): it is written exactly once, by
     * {@code Action.publish}, and that immutability is what the pattern rests
     * on.
     *
     * <p>The {@code IN} lists are never empty -- {@code SubscriptionFilter}
     * substitutes the full vocabulary for a subscriber who chose nothing -- so
     * there is no unfiltered branch here.
     *
     * <p>Ascending with an id tiebreaker: a digest reads as a story told
     * forwards, and two actions published in one transaction share a
     * {@code published_at} to the microsecond. No {@code Limit}, because
     * truncating would drop actions the watermark then moves past.
     *
     * @param watermark {@code app_user.last_digest_sent_at} for this subscriber
     * @param cutoff    {@code now} minus the lag window, and where the watermark
     *                  moves if the send succeeds
     * @param now       passed in, never {@code CURRENT_TIMESTAMP} (ADR-019)
     */
    @Query("""
            select new com.fivegoodmins.fivegoodmins.digest.DigestActionRow(
                       a.id, a.title, a.actionType,
                       substring(a.description, 1, :excerptChars))
            from Action a
            where a.status = com.fivegoodmins.fivegoodmins.action.ActionStatus.PUBLISHED
              and a.publishedAt > :watermark
              and a.publishedAt <= :cutoff
              and a.endsAt > :now
              and exists (select 1 from Action ac join ac.categories c
                          where ac.id = a.id and c in :categories)
              and exists (select 1 from Action ar join ar.regions r
                          where ar.id = a.id and r in :regions)
            order by a.publishedAt asc, a.id asc
            """)
    List<DigestActionRow> findMatching(@Param("watermark") Instant watermark,
                                       @Param("cutoff") Instant cutoff,
                                       @Param("now") Instant now,
                                       @Param("categories") Collection<Category> categories,
                                       @Param("regions") Collection<Region> regions,
                                       @Param("excerptChars") int excerptChars);

    /**
     * The category badges for a set of actions, as {@code (actionId, category)}
     * pairs -- one query for every action in a digest rather than one per
     * action.
     *
     * <p>The caller must not pass an empty collection; {@link DigestQueries}
     * returns before reaching here when there is nothing to send.
     */
    @Query("""
            select new com.fivegoodmins.fivegoodmins.digest.DigestCategoryTag(a.id, c)
            from Action a join a.categories c
            where a.id in :actionIds
            """)
    List<DigestCategoryTag> findCategories(@Param("actionIds") Collection<Long> actionIds);

    /** The region badges. See {@link #findCategories}. */
    @Query("""
            select new com.fivegoodmins.fivegoodmins.digest.DigestRegionTag(a.id, r)
            from Action a join a.regions r
            where a.id in :actionIds
            """)
    List<DigestRegionTag> findRegions(@Param("actionIds") Collection<Long> actionIds);
}
