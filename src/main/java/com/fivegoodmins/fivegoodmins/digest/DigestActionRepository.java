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
 * The digest's matching query -- ADR-005's central piece of logic -- and the
 * two that fetch the badges for what it returns.
 *
 * <p><strong>Here rather than on {@code ActionRepository}, which is a decision
 * with a cost.</strong> {@code action/ActionQueries} exists so that no other
 * feature holds {@code ActionRepository}, and the consistent thing would be a
 * digest method on it. What tipped it the other way is that this query is not
 * the action listing's shape and never will be: it takes a per-subscriber
 * watermark and two bound {@code IN} lists, it is read once a night by one
 * caller, and the rule it encodes -- what counts as new for this reader -- is
 * the digest's rule and nothing else's. Under package-by-feature (ADR-003) a
 * read model belongs to the feature that reads it.
 *
 * <p>The honest cost: {@code digest/} now knows the shape of {@code action}'s
 * data, so a change to how an action is tagged touches two packages. The
 * narrowing that keeps it bearable is the same one {@code subscription/} uses
 * -- {@code Repository}, not {@code JpaRepository}, so this package can read
 * three queries' worth of {@code action} and nothing else, and cannot write to
 * it at all. If a second feature ever needs this shape, the answer is to move
 * it to {@code ActionQueries} and have both call that.
 *
 * <p><strong>Three queries, not one join.</strong> ADR-005 is explicit that
 * matching uses {@code EXISTS} rather than a join because an action tagged
 * {@code BURGAS, NATIONAL} would come back twice for a Burgas subscriber. The
 * badges reintroduce exactly that risk -- they are the rows a join would
 * duplicate on -- so they are fetched separately, keyed on the ids the first
 * query returned, and grouped in Java by {@link DigestQueries}. One extra
 * round trip per facet per subscriber, in a job that runs once a day against a
 * free tier capped at 300 sends (ADR-005).
 *
 * <p>ADR-005 weighed "a query per action or a join that returns an action once
 * per tag" and rejected both for the welcome email. This is the third option it
 * did not name: one query per <em>facet</em>, for all of that subscriber's
 * actions at once.
 */
interface DigestActionRepository extends Repository<Action, Long> {

    /**
     * Everything one subscriber has not been told about yet and could still
     * act on.
     *
     * <p>ADR-005's query, clause for clause:
     *
     * <ul>
     *   <li><strong>{@code status = PUBLISHED}</strong> -- the only status that
     *       is ever mailed.</li>
     *   <li><strong>{@code publishedAt > :watermark}</strong> -- strictly
     *       greater, so the action that moved the watermark last time is not
     *       sent twice. This is the clause that keys the digest on
     *       {@code published_at} and never {@code created_at} or
     *       {@code updated_at}: the first would miss every action created
     *       before it was approved, the second would push a typo fix above
     *       every watermark on the platform. {@code published_at} is written
     *       exactly once ({@code Action.publish}) and that immutability is what
     *       the whole pattern rests on.</li>
     *   <li><strong>{@code publishedAt <= :cutoff}</strong> -- the lag window,
     *       and the clause most likely to look redundant to somebody tidying
     *       this up. {@code now()} is transaction start time, so a publish
     *       beginning at 06:59:59 is invisible to a job starting at 07:00:00
     *       which would then move the watermark past it; the row commits with
     *       an earlier {@code published_at} and is never mailed to anybody,
     *       ever. Excluding the last few minutes -- and advancing the watermark
     *       only to this same cutoff -- is what closes that.</li>
     *   <li><strong>{@code endsAt > :now}</strong> -- never mail an action that
     *       has already expired. Derived per query; there is no
     *       {@code EXPIRED} status and no job that sets one.</li>
     *   <li><strong>two {@code exists} subqueries</strong> -- one per facet,
     *       which is what stops a multiply-tagged action appearing twice and
     *       what lets Postgres stop at the first matching tag.</li>
     * </ul>
     *
     * <p><strong>The {@code IN} lists are never empty.</strong>
     * {@code SubscriptionFilter} substitutes the complete vocabulary for a
     * subscriber who has chosen nothing, so this query has no "unfiltered"
     * branch and never depends on how Hibernate renders an empty {@code IN} --
     * which has changed between versions.
     *
     * <p>The subqueries correlate on {@code a.id} rather than on the entity
     * (<code>a2 = a</code>), which both work in HQL; the id form is the one
     * that reads unambiguously and does not depend on how the dialect compares
     * entity references.
     *
     * <p><strong>The ordering carries its tiebreaker</strong>, like every list
     * query here: two actions published in one transaction share a
     * {@code published_at} to the microsecond, and without {@code id} the
     * contents of one email would vary between two identical runs. Ascending,
     * unlike the listing -- a digest is read as "what happened since you last
     * heard from us", which is a story told forwards.
     *
     * <p><strong>No {@code Limit}.</strong> A subscriber the job has not
     * reached for a week gets the week, because truncating would drop actions
     * the watermark then moves past -- and ADR-005's claim is precisely that
     * nothing is missed when the job fails for two days. The practical bound is
     * however long it has been broken; how much of it to render is the
     * template's decision, not the query's.
     *
     * @param watermark {@code app_user.last_digest_sent_at} for this subscriber
     * @param cutoff    {@code now} minus the lag window, and also where the
     *                  watermark will move to if the send succeeds
     * @param now       passed in, never {@code CURRENT_TIMESTAMP} (ADR-019), so
     *                  a test can put a day wherever it likes
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
     * pairs -- one query for every action in one subscriber's digest rather
     * than one per action.
     *
     * <p>The caller must not pass an empty collection. There is no work to do
     * for an empty digest and no email either, so {@link DigestQueries} returns
     * before reaching here; an empty {@code IN} is the thing this codebase
     * declines to depend on everywhere else, and that does not stop being true
     * in a query nobody looks at.
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
