package com.fivegoodmins.fivegoodmins.shared.dev;

import com.fivegoodmins.fivegoodmins.shared.FlashMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Clock;
import java.time.Instant;

/**
 * The living style guide from section 8 of the frontend brief: one page that
 * renders every shared component, so the design can be checked in both
 * languages without clicking through five pages that do not exist yet.
 *
 * <p><strong>{@code @Profile("dev")}, so it does not exist in production.</strong>
 * The deployed jar starts with no profile (pom.xml activates {@code dev} for
 * {@code spring-boot:run} only), so this controller is never registered there
 * and {@code /dev/styleguide} is a 404 on the live site. That is the whole
 * security argument for the page: the path is permitted in
 * {@code SecurityConfig} unconditionally, because permitting a path nothing
 * serves is simpler to read -- and to verify -- than a security rule that
 * changes shape per profile.
 *
 * <p>This is a developer tool, not a user-facing page, which is the one place
 * the "no hardcoded strings" rule (ADR-008) does not apply: its own section
 * headings are English text in the template. Everything it DEMONSTRATES
 * resolves through the bundles, which is the point -- a missing taxonomy
 * label shows up here as a blank badge before it shows up on a real page as a
 * 500.
 */
@Profile("dev")
@Controller
class StyleGuideController {

    private final Clock clock;

    StyleGuideController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/dev/styleguide")
    String styleGuide(Model model) {
        // The flash fragment reads one model attribute; three examples here
        // let the page show all three levels at once, which a real redirect
        // never can.
        model.addAttribute("flashSuccess",
                new FlashMessage(FlashMessage.Level.SUCCESS, "verification.result.confirmed.body"));
        model.addAttribute("flashError",
                new FlashMessage(FlashMessage.Level.ERROR, "login.failed"));
        model.addAttribute("flashInfo",
                new FlashMessage(FlashMessage.Level.INFO, "verification.notice.expiry", 7));
        return "dev/styleguide";
    }

    /**
     * The htmx smoke test, and the reason it is worth a route: ADR-002 says
     * every project loses an hour to htmx POSTs returning 403, because the
     * CSRF token has to reach them through {@code hx-headers} in the layout.
     * This button proves that wiring works from the browser rather than from
     * a test -- and the matching test proves the token is what makes it work,
     * by sending the same request without one.
     *
     * <p>Returns a fragment, not a page: that is the shape every htmx
     * endpoint in this application will have (ADR-001).
     */
    @PostMapping("/dev/styleguide/htmx-check")
    String htmxCheck(Model model) {
        // Injected Clock, never Instant.now() (ADR-019) -- even here, because
        // the rule is only a rule if it holds in the throwaway cases too.
        model.addAttribute("checkedAt", Instant.now(clock));
        return "dev/styleguide :: htmx-result";
    }
}
