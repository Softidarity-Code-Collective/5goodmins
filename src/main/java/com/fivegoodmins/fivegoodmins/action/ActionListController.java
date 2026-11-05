package com.fivegoodmins.fivegoodmins.action;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Clock;

/**
 * Page 1 of the frontend brief, as a structural placeholder.
 *
 * <p><strong>Why it exists now rather than with the rest of page 1.</strong>
 * The welcome email (ADR-005) carries a call to action, and a link in a
 * stranger's first email that lands on a login form is the product's first
 * impression. Everything else page 1 owes — category, region and type filters,
 * free-text search, htmx partial swaps, pagination, the active-filter summary
 * — is absent on purpose and is not what the email needed. The one thing that
 * had to be true today is that the link resolves to the actions.
 *
 * <p><strong>What it is not.</strong> There is no paging, so it shows the
 * newest {@link #PAGE_SIZE} and stops; the brief's pagination is a different
 * query, with an offset and a total to pay for, and guessing at it now would be
 * a shape page 1 has to undo. There is no filtering, so there is no filter
 * state to encode in a URL. Both are listed as absent rather than stubbed,
 * because a stub filter that silently does nothing is worse than no filter.
 *
 * <p>{@link ActionQueries} rather than the repository, and projections rather
 * than entities, so nothing in the template can reach for a lazy association
 * with {@code open-in-view=false} (ADR-003, ADR-013).
 */
@Controller
class ActionListController {

    /**
     * How many actions the placeholder shows. Not a page size in the real
     * sense — there is no second page to go to — which is why the number lives
     * here and not in configuration.
     */
    private static final int PAGE_SIZE = 20;

    private final ActionQueries actions;
    private final Clock clock;

    ActionListController(ActionQueries actions, Clock clock) {
        this.actions = actions;
        this.clock = clock;
    }

    @GetMapping(ActionPaths.LIST)
    String list(Model model) {
        model.addAttribute("actions", actions.newestActive(clock.instant(), PAGE_SIZE));
        return "action/list";
    }
}
