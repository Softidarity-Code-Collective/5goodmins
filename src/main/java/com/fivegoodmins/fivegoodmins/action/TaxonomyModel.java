package com.fivegoodmins.fivegoodmins.action;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * The four closed vocabularies -- action type, category, region, status --
 * on the model of every page, so a template never has to reach for
 * {@code T(com.fivegoodmins...Category).values()}.
 *
 * <p><strong>Why not {@code T(...)}:</strong> a Thymeleaf template that names
 * a Java class by its fully qualified name is a template that breaks on a
 * package rename with no compiler to notice, it needs SpEL type access turned
 * on, and it puts a decision about which values a page may offer inside the
 * markup. The filter sidebar, the submit form and the admin queue all need
 * the same lists; this is the one place they come from.
 *
 * <p>The display text for each constant is never the constant: templates
 * render {@code #{'category.' + ${category}}} and the bundles hold the
 * Bulgarian and English (ADR-008). {@code TaxonomyMessagesTest} is what makes
 * sure every constant here has both.
 *
 * <p>Static lists built once at class initialization, not per request:
 * {@code values()} clones its array on every call, and these four never
 * change between deployments. The cost at request time is four references on
 * a map.
 *
 * <p>In {@code action} rather than {@code shared} because this is the action
 * vocabulary (ADR-003: package by feature). It is a {@code @ControllerAdvice}
 * and therefore applies to every controller including {@code user} and
 * {@code shared.security} -- that is the web layer's own cross-cutting
 * concern, the same way {@code LocaleSwitch} is, not a dependency between
 * features.
 */
@ControllerAdvice
class TaxonomyModel {

    private static final List<ActionType> TYPES = List.of(ActionType.values());
    private static final List<Category> CATEGORIES = List.of(Category.values());
    private static final List<Region> REGIONS = List.of(Region.values());
    private static final List<ActionStatus> STATUSES = List.of(ActionStatus.values());

    @ModelAttribute("actionTypes")
    List<ActionType> actionTypes() {
        return TYPES;
    }

    @ModelAttribute("categories")
    List<Category> categories() {
        return CATEGORIES;
    }

    /**
     * All 30, in declaration order. The order is a decision the enum already
     * made and the sidebar inherits: sorting them alphabetically would sort
     * them by their English identifiers, which is not the order a Bulgarian
     * reader expects and not the order {@code NATIONAL} belongs at the end of.
     */
    @ModelAttribute("regions")
    List<Region> regions() {
        return REGIONS;
    }

    /**
     * Only ever rendered on "my actions" and the admin queues -- the public
     * pages show published actions and nothing else -- but on the model
     * everywhere, because the alternative is one more thing for a controller
     * to remember.
     */
    @ModelAttribute("actionStatuses")
    List<ActionStatus> actionStatuses() {
        return STATUSES;
    }
}
