package com.fivegoodmins.fivegoodmins.subscription;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.AppUserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Page 13 end to end: the subscription preferences form.
 *
 * <p>A structural placeholder's worth of markup, so the assertions here are
 * about the things a redesign must not break rather than about layout: the
 * checkbox names Spring binds, the labels coming from the bundle, and the
 * sentence that explains what an empty selection means.
 *
 * <p>The principal comes from the real {@code UserDetailsService} rather than from
 * {@code @WithMockUser}, because the controller reads {@code app_user.id} off
 * it -- which is what the principal carries the id for, and what keeps this
 * page from looking an account up by address.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PreferencesWebTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");

    MockMvc mockMvc;

    @Autowired AppUserRepository users;
    // The Spring interface, not AppUserDetailsService, which is package-private
    // in shared.security -- and widening a class so a test in another package
    // can name it is the wrong trade. By type it resolves to the same bean.
    @Autowired UserDetailsService userDetails;
    @Autowired SubscriptionService subscriptions;
    @Autowired EntityManager em;

    private AppUser reader;
    private UserDetails principal;

    @BeforeEach
    void buildMockMvc(@Autowired WebApplicationContext context) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").locale(BULGARIAN))
                .build();

        reader = new AppUser("preferences@seed.example", "{bcrypt}x", "Чит", Instant.now());
        reader.markEmailVerified(Instant.now());
        reader = users.saveAndFlush(reader);
        principal = userDetails.loadUserByUsername(reader.getEmail());
    }

    private MockHttpServletRequestBuilder save() {
        return post(SubscriptionPaths.PREFERENCES).with(csrf()).with(user(principal));
    }

    /**
     * Reads the saved preferences back from the database.
     *
     * <p><strong>Flush before clear, and the order is the whole point.</strong>
     * This page saves by mutating a managed {@code AppUser} and letting dirty
     * checking write it at flush -- so inside this test's single transaction
     * the changes are still pending when the controller returns, and a bare
     * {@code em.clear()} <em>discards</em> them. Written that way, every save
     * test here failed with an empty selection while the controller was
     * perfectly correct.
     *
     * <p>Worth keeping next to {@code UnsubscribeWebTest}, which clears without
     * flushing and must: that path writes with a bulk JPQL statement that has
     * already reached the database, and the stale thing there is the cached
     * entity. Same two lines, opposite problems -- one is a write that has not
     * happened yet, the other a read that happened too early.
     */
    private SubscriptionPreferences stored() {
        em.flush();
        em.clear();
        return subscriptions.preferencesOf(reader.getId());
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Test
    void requiresAnAccount() throws Exception {
        // The asymmetry with /unsubscribe, which works signed out: changing
        // what you follow is an account setting.
        mockMvc.perform(get(SubscriptionPaths.PREFERENCES))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void offersEveryCategoryAndRegionFromTheMessageBundle() throws Exception {
        mockMvc.perform(get(SubscriptionPaths.PREFERENCES).with(user(principal)))
                .andExpect(status().isOk())
                // Labels, not constants: the lists come from TaxonomyModel and
                // the text from the bundle, so adding a category stays an enum
                // constant plus two properties lines.
                .andExpect(content().string(containsString("Природа")))
                .andExpect(content().string(containsString("Стара Загора")))
                .andExpect(content().string(containsString("Цяла България")));
    }

    @Test
    void saysThatTickingNothingMeansEverything() throws Exception {
        // The most surprising thing about this form (ADR-023). Without the
        // sentence, a page with sixty empty checkboxes and a live digest looks
        // broken -- so this string is not decoration and the test says so.
        mockMvc.perform(get(SubscriptionPaths.PREFERENCES).with(user(principal)))
                .andExpect(content().string(containsString("Ако не изберете нищо, получавате всичко")));
    }

    @Test
    void showsTheStoredSelectionAsChecked() throws Exception {
        subscriptions.save(reader.getId(), java.util.Set.of(Category.NATURE),
                java.util.Set.of(Region.VARNA), true);
        em.flush();
        em.clear();

        mockMvc.perform(get(SubscriptionPaths.PREFERENCES).with(user(principal)))
                .andExpect(content().string(containsString("value=\"NATURE\" checked")))
                .andExpect(content().string(containsString("value=\"VARNA\" checked")));
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    @Test
    void savesASelectionAndRedirects() throws Exception {
        mockMvc.perform(save()
                        .param("categories", "NATURE", "ANIMALS")
                        .param("regions", "VARNA")
                        .param("digestEnabled", "true"))
                .andExpect(redirectedUrl(SubscriptionPaths.PREFERENCES));

        SubscriptionPreferences saved = stored();
        assertThat(saved.categories()).containsExactlyInAnyOrder(Category.NATURE, Category.ANIMALS);
        assertThat(saved.regions()).containsExactly(Region.VARNA);
        assertThat(saved.digestEnabled()).isTrue();
    }

    @Test
    void treatsAnEmptyPostAsAValidSave() throws Exception {
        subscriptions.save(reader.getId(), java.util.Set.of(Category.NATURE),
                java.util.Set.of(Region.VARNA), true);
        em.flush();
        em.clear();

        // A browser sends no parameter at all for an unticked checkbox group,
        // so this is what "clear my selection" looks like on the wire. It is a
        // save, not a validation error -- and it means "everything" afterwards.
        mockMvc.perform(save().param("digestEnabled", "true"))
                .andExpect(redirectedUrl(SubscriptionPaths.PREFERENCES));

        assertThat(stored().categories()).isEmpty();
        assertThat(subscriptions.effectiveFilterFor(reader.getId()).categories())
                .containsExactlyInAnyOrder(Category.values());
    }

    @Test
    void turnsTheDigestOffWhenTheBoxIsNotTicked() throws Exception {
        // The same column the unsubscribe link writes, from the signed-in side.
        mockMvc.perform(save().param("categories", "NATURE"))
                .andExpect(redirectedUrl(SubscriptionPaths.PREFERENCES));

        assertThat(stored().digestEnabled()).isFalse();
    }

    @Test
    void turnsTheDigestBackOn() throws Exception {
        mockMvc.perform(save()).andExpect(redirectedUrl(SubscriptionPaths.PREFERENCES));
        assertThat(stored().digestEnabled()).isFalse();

        // The recovery an unsubscribe link does not offer: somebody who opted
        // out can opt back in, which is why the switch is a plain boolean
        // rather than a one-way door.
        mockMvc.perform(save().param("digestEnabled", "true"))
                .andExpect(redirectedUrl(SubscriptionPaths.PREFERENCES));
        assertThat(stored().digestEnabled()).isTrue();
    }

    @Test
    void refusesASaveWithoutACsrfToken() throws Exception {
        mockMvc.perform(post(SubscriptionPaths.PREFERENCES).with(user(principal))
                        .param("digestEnabled", "true"))
                .andExpect(status().isForbidden());
    }
}
