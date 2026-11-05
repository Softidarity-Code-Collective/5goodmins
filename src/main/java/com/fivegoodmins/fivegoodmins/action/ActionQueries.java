package com.fivegoodmins.fivegoodmins.action;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * The read side of {@code action/}, and the only part of it another feature is
 * meant to call.
 *
 * <p><strong>This exists so that no other feature holds
 * {@link ActionRepository}.</strong> {@code user/} needs one query in order to
 * build the welcome email (ADR-005) and nothing else from this package; given
 * the repository it would have been handed every method on
 * {@code JpaRepository} as well, which is a dependency on {@code action/}'s
 * storage rather than on its behaviour. Under package-by-feature (ADR-003) the
 * stated goal is that extracting a module stays trivial, and that is only true
 * while the surface another feature touches is the one deliberately offered.
 *
 * <p>It is a pass-through today, and that is the honest shape: there are no
 * invariants here yet. What it buys now is the seam — and the two callers it
 * already has, the welcome email and the action listing, are the two that would
 * otherwise each have reached for the repository.
 *
 * <p>{@code @Service} rather than {@code @Component} for the same reason the
 * rest of this codebase uses it: the stereotype says which layer this is, and
 * it is the layer entities do not leave (ADR-003). Everything here returns
 * projections.
 */
@Service
public class ActionQueries {

    private final ActionRepository actions;

    ActionQueries(ActionRepository actions) {
        this.actions = actions;
    }

    /**
     * The newest actions a visitor could act on right now.
     *
     * <p>The excerpt length is applied here, from
     * {@link ActionSummary#EXCERPT_CHARS}, and passed to the query as a bound
     * parameter — so the number lives in one place and this is the only code
     * that applies it.
     *
     * @param now   passed in, never read from a clock here (ADR-019).
     * @param limit a cap, not a page. Callers that need paging need a different
     *              method, because an offset and a total are a different query.
     */
    public List<ActionSummary> newestActive(Instant now, int limit) {
        return actions.findNewestActive(now, ActionSummary.EXCERPT_CHARS, Limit.of(limit));
    }
}
