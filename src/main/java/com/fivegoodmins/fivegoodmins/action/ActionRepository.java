package com.fivegoodmins.fivegoodmins.action;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ActionRepository extends JpaRepository<Action, Long> {

    Optional<Action> findByNormalizedUrl(String normalizedUrl);

    /** Submission quota -- ten per rolling 24h (ADR-016). */
    long countByAuthorIdAndCreatedAtAfter(Long authorId, Instant since);

    /**
     * Floor for promotion to POSTER -- three approved actions (ADR-016).
     *
     * Counts published_at, not status = PUBLISHED. The state graph is cyclic, so
     * status is the *current* state while the threshold asks a *historical*
     * question: has an admin ever approved this action? An author who withdrew
     * two of five approved actions would otherwise count as three, and one whose
     * action was later taken down would lose credit for work that was approved.
     * published_at is written once and never cleared, so it is the record of
     * "has been published at least once".
     */
    long countByAuthorIdAndPublishedAtIsNotNull(Long authorId);

    /**
     * The newest actions a visitor could act on right now, as projections
     * (ADR-003). The welcome email carries five of them (ADR-005).
     *
     * <p><strong>Active is derived, never stored.</strong> {@code PUBLISHED}
     * plus an {@code ends_at} in the future, computed per query -- there is no
     * {@code EXPIRED} status and no job that sets one, because a status column
     * that depends on the clock is a column that is wrong between runs.
     *
     * <p><strong>{@code now} is a parameter, not {@code CURRENT_TIMESTAMP}.</strong>
     * ADR-019: the caller passes the instant, so a test can ask what this
     * returns on a day of its choosing instead of arranging fixtures around the
     * real calendar.
     *
     * <p><strong>The ordering carries its tiebreaker,</strong> and the pair is
     * not decoration: two actions published in one transaction share a
     * {@code published_at} to the microsecond, and without {@code id desc} the
     * database may return them in either order -- so "the five newest" would
     * quietly differ between two identical requests. The column order also
     * matches {@code idx_action_published} exactly, tiebreaker included, so the
     * sort comes free from the index scan rather than from a sort node.
     *
     * <p>{@code Limit} rather than {@code Pageable}: there is no page, no offset
     * and no total to pay for. Five is a cap, not a window.
     *
     * <p><strong>The description is cut to an excerpt in SQL</strong>, and the
     * length is a bound parameter rather than a literal, so
     * {@link ActionSummary#EXCERPT_CHARS} exists in exactly one place.
     * {@code ActionQueries} supplies it. An earlier version wrote the number
     * twice and defended the duplication as unavoidable because "JPQL cannot
     * read a constant" — it does not have to, and documenting a duplication you
     * had a choice about is a worse answer than either removing it or owning
     * it. Reading a 5,000 character column in order to display 140 of it is
     * affordable once per account verification and not once per subscriber per
     * day, which is the shape the digest will reuse.
     */
    @Query("""
            select new com.fivegoodmins.fivegoodmins.action.ActionSummary(
                       a.id, a.title, a.actionType, substring(a.description, 1, :excerptChars))
            from Action a
            where a.status = com.fivegoodmins.fivegoodmins.action.ActionStatus.PUBLISHED
              and a.endsAt > :now
            order by a.publishedAt desc, a.id desc
            """)
    List<ActionSummary> findNewestActive(@Param("now") Instant now,
                                         @Param("excerptChars") int excerptChars,
                                         Limit limit);
}
