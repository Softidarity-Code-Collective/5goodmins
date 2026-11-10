package com.fivegoodmins.fivegoodmins.subscription;

import com.fivegoodmins.fivegoodmins.shared.FlashMessage;
import com.fivegoodmins.fivegoodmins.shared.security.AppUserDetails;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Page 13 of the frontend brief: pick categories and regions to follow, and
 * turn the daily digest on or off.
 *
 * <p><strong>The backend of that page, with a structural placeholder for
 * markup</strong> -- the same standing as the action listing and the
 * registration form. What is here is the binding, the save and the redirect;
 * what is not is any of the design.
 *
 * <p><strong>Authenticated, and therefore not the whole story.</strong> The
 * digest's own opt-out cannot require a login -- a reader with a digest in
 * front of them has no reason to have a session, and the EU requires the link
 * to work regardless (ADR-005). {@code UnsubscribeController} is that half.
 * This page is where someone who is signed in changes their mind in either
 * direction, including back.
 *
 * <p>No path constants of its own: {@link SubscriptionPaths} holds them,
 * because {@code SecurityConfig} needs the same two strings.
 */
@Controller
class PreferencesController {

    private final SubscriptionService subscriptions;

    PreferencesController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    /**
     * Renders the form with the stored selection already in it.
     *
     * <p>The categories and regions the page offers are not built here: they
     * come from {@code TaxonomyModel}, the {@code @ControllerAdvice} that puts
     * all four vocabularies on every model. So this method supplies only what
     * is specific to this user, and adding a category stays an enum constant
     * plus two bundle lines plus a migration, with no controller change.
     *
     * <p>{@code @AuthenticationPrincipal}, not {@code Principal} or a
     * {@code SecurityContextHolder} read: the principal carries
     * {@code app_user.id} for exactly this reason, so the page does not look
     * an account up by address in order to find the row it is about.
     */
    @GetMapping(SubscriptionPaths.PREFERENCES)
    String preferences(@AuthenticationPrincipal AppUserDetails principal,
                       @ModelAttribute("form") SubscriptionForm form) {
        SubscriptionPreferences stored = subscriptions.preferencesOf(principal.getId());
        form.setCategories(stored.categories());
        form.setRegions(stored.regions());
        form.setDigestEnabled(stored.digestEnabled());
        return "subscription/preferences";
    }

    /**
     * Saves the posted selection and redirects.
     *
     * <p><strong>Redirect rather than render, so a refresh does not re-post.</strong>
     * The save is idempotent, so a double submission would be harmless -- but
     * the browser's "confirm resubmission" dialogue on a settings page is a
     * worse answer than a GET, and the flash message needs a redirect to be
     * worth having.
     *
     * <p><strong>{@code @Valid} with nothing to validate, and that is
     * deliberate rather than leftover.</strong> {@link SubscriptionForm}
     * carries no constraints: every selection size from zero to the whole
     * vocabulary is legal, and the only way this form can be wrong is a value
     * outside the enum, which Spring's conversion rejects into the
     * {@code BindingResult} before any validator runs. The annotation and the
     * check below are what turn that into a redisplayed page instead of a
     * 400 -- and they are what the first real constraint will need already in
     * place.
     */
    @PostMapping(SubscriptionPaths.PREFERENCES)
    String save(@AuthenticationPrincipal AppUserDetails principal,
                @Valid @ModelAttribute("form") SubscriptionForm form,
                BindingResult binding,
                RedirectAttributes attributes) {
        if (binding.hasErrors()) {
            // Only reachable by a hand-made request: the page's checkboxes are
            // rendered from TaxonomyModel's lists, so a browser cannot post a
            // value the enum does not have. Redisplaying rather than refusing,
            // because this is the shape the first real constraint will use.
            return "subscription/preferences";
        }

        subscriptions.save(principal.getId(),
                form.getCategories(),
                form.getRegions(),
                form.isDigestEnabled());

        // The first flash message in the application. FlashMessage's own notes
        // say the convenience constructors come back with the first controller
        // that has something to carry; one call site is not yet three, so this
        // builds one directly and leaves that file alone.
        attributes.addFlashAttribute("flash",
                new FlashMessage(FlashMessage.Level.SUCCESS, "subscription.preferences.saved"));
        return "redirect:" + SubscriptionPaths.PREFERENCES;
    }
}
