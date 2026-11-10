package com.fivegoodmins.fivegoodmins.subscription;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.shared.token.SignedTokenService;
import com.fivegoodmins.fivegoodmins.shared.token.TokenPurpose;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.AppUserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Page 9 end to end: the unsubscribe link out of a digest, <strong>with no
 * session at all</strong>.
 *
 * <p><strong>The signed-out part is the requirement, not a convenience.</strong>
 * A reader holding a digest has no reason to be logged in, and the EU requires
 * the opt-out to work regardless (ADR-005). Every path in this application
 * except a handful is behind {@code .anyRequest().authenticated()}, so the
 * first two tests here are what keep this one outside it -- the same role the
 * first test in {@code ActionListWebTest} plays for the welcome email's link.
 *
 * <p><strong>And the third test is the one that would otherwise be only a
 * comment.</strong> ADR-005 asked for one-click unsubscribe; this feature
 * declines that and splits it into a GET that confirms and a POST that acts,
 * because mail clients and link scanners prefetch URLs in email. A GET that
 * unsubscribed would be performed by the prefetcher rather than the reader.
 *
 * <p>{@code @SpringBootTest} rather than {@code @WebMvcTest}: the latter loads
 * neither {@code MessageSourceConfig} nor {@code LocaleConfig}, so every
 * Bulgarian assertion would fail for the wrong reason. The locale is bound on
 * {@code defaultRequest} because MockMvc sends {@code Accept-Language: en}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class UnsubscribeWebTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");

    MockMvc mockMvc;

    @Autowired AppUserRepository users;
    @Autowired SignedTokenService tokens;
    @Autowired SubscriptionService subscriptions;
    @Autowired EntityManager em;

    private AppUser reader;

    @BeforeEach
    void buildMockMvc(@Autowired WebApplicationContext context) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").locale(BULGARIAN))
                .build();
        reader = users.saveAndFlush(
                new AppUser("unsubscribe@seed.example", "{bcrypt}x", "Чит", Instant.now()));
    }

    private String validToken() {
        return tokens.issue(reader.getId(), TokenPurpose.UNSUBSCRIBE);
    }

    /**
     * Reads {@code digest_enabled} back from the database rather than from the
     * persistence context.
     *
     * <p><strong>The {@code em.clear()} is load-bearing, and finding out why is
     * worth recording.</strong> Without it these assertions failed while the
     * page under test correctly said "you are unsubscribed":
     * {@code setDigestEnabled} is a bulk JPQL statement, executed by the
     * database, so the first-level cache is never told -- and {@code findById}
     * then returns the {@code AppUser} this test loaded in {@code @BeforeEach},
     * still saying {@code true}.
     *
     * <p>That is exactly the hazard {@code SubscriptionRepository}'s own notes
     * describe when they explain why there is no {@code clearAutomatically} on
     * it: the trap is a persistence context holding a row the database has
     * changed. It bites here and not in production because {@code @Transactional}
     * on this class puts the test and the controller in one transaction and one
     * persistence context, while the real request has its own short transaction
     * and holds no entity at all. So the fix belongs in the test, not on the
     * query -- but the day a flow calls that method while holding an
     * {@code AppUser}, this is what it will look like.
     */
    private boolean stillSubscribed() {
        em.clear();
        return users.findById(reader.getId()).orElseThrow().isDigestEnabled();
    }

    // ------------------------------------------------------------------
    // Reachable without a login
    // ------------------------------------------------------------------

    @Test
    void showsTheConfirmationWithoutAnAccount() throws Exception {
        mockMvc.perform(get(SubscriptionPaths.UNSUBSCRIBE)
                        .param(SubscriptionPaths.TOKEN_PARAMETER, validToken()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Отписване от бюлетина")));
    }

    @Test
    void unsubscribesWithoutAnAccount() throws Exception {
        mockMvc.perform(post(SubscriptionPaths.UNSUBSCRIBE).with(csrf())
                        .param(SubscriptionPaths.TOKEN_PARAMETER, validToken()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Отписахте се")));

        assertThat(stillSubscribed()).isFalse();
    }

    // ------------------------------------------------------------------
    // The prefetch problem
    // ------------------------------------------------------------------

    @Test
    void theGetChangesNothing() throws Exception {
        mockMvc.perform(get(SubscriptionPaths.UNSUBSCRIBE)
                        .param(SubscriptionPaths.TOKEN_PARAMETER, validToken()))
                .andExpect(status().isOk());

        // Gmail prefetches links, Outlook's Safe Links fetches them to scan
        // them, and a preview generator fetches them to render a card. If the
        // GET unsubscribed, all three would do it on the reader's behalf and
        // the reader would find out by not receiving tomorrow's digest.
        assertThat(stillSubscribed()).isTrue();
    }

    @Test
    void rendersTheConfirmationEvenWithNoTokenAtAll() throws Exception {
        // A URL somebody typed. required = false, so this is a page rather than
        // a 400 -- and it looks the same as a page reached with a real token,
        // because validating on the GET would make this an oracle for whether a
        // given token is genuine.
        mockMvc.perform(get(SubscriptionPaths.UNSUBSCRIBE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Отписване от бюлетина")));
    }

    // ------------------------------------------------------------------
    // What a bad token gets
    // ------------------------------------------------------------------

    @Test
    void showsADeadLinkForATokenThatDoesNotVerify() throws Exception {
        mockMvc.perform(post(SubscriptionPaths.UNSUBSCRIBE).with(csrf())
                        .param(SubscriptionPaths.TOKEN_PARAMETER, "1.9999999999.forged"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Връзката вече не работи")));

        assertThat(stillSubscribed()).isTrue();
    }

    @Test
    void showsADeadLinkForAVerificationTokenOfferedHere() throws Exception {
        // The purpose is bound into the signature, so a link from a
        // verification email cannot opt somebody out of the digest. Same key,
        // same account, same format -- and it does not verify.
        String verification = tokens.issue(reader.getId(), TokenPurpose.EMAIL_VERIFICATION);

        mockMvc.perform(post(SubscriptionPaths.UNSUBSCRIBE).with(csrf())
                        .param(SubscriptionPaths.TOKEN_PARAMETER, verification))
                .andExpect(content().string(containsString("Връзката вече не работи")));

        assertThat(stillSubscribed()).isTrue();
    }

    @Test
    void refusesThePostWithoutACsrfToken() throws Exception {
        // There is no session to protect here, so this is not about account
        // takeover: it stops a page on another site POSTing somebody's
        // unsubscribe link for them, which is the prefetch problem with a human
        // behind it. The confirmation page carries the token -- Thymeleaf's
        // Spring dialect adds the hidden input to any th:action form, for an
        // anonymous visitor too.
        mockMvc.perform(post(SubscriptionPaths.UNSUBSCRIBE)
                        .param(SubscriptionPaths.TOKEN_PARAMETER, validToken()))
                .andExpect(status().isForbidden());

        assertThat(stillSubscribed()).isTrue();
    }

    // ------------------------------------------------------------------
    // Idempotence
    // ------------------------------------------------------------------

    @Test
    void sayingItTwiceStillSaysYouAreUnsubscribed() throws Exception {
        String token = validToken();

        mockMvc.perform(post(SubscriptionPaths.UNSUBSCRIBE).with(csrf())
                        .param(SubscriptionPaths.TOKEN_PARAMETER, token))
                .andExpect(content().string(containsString("Отписахте се")));

        // No token table records that it was used, which is only acceptable
        // because replay changes nothing -- so the second attempt must confirm
        // rather than report a dead link.
        mockMvc.perform(post(SubscriptionPaths.UNSUBSCRIBE).with(csrf())
                        .param(SubscriptionPaths.TOKEN_PARAMETER, token))
                .andExpect(content().string(containsString("Отписахте се")));

        assertThat(stillSubscribed()).isFalse();
    }

    // ------------------------------------------------------------------
    // The other half of the pair
    // ------------------------------------------------------------------

    @Test
    void sendsAnAnonymousVisitorToTheLoginFormForThePreferencesPage() throws Exception {
        // The deliberate asymmetry: the opt-out works signed out, changing what
        // you follow does not. SubscriptionPaths.PREFERENCES is not in
        // SecurityConfig's permit list, so it falls through to
        // anyRequest().authenticated() -- and this is the assertion that
        // notices if somebody adds it there by symmetry.
        mockMvc.perform(get(SubscriptionPaths.PREFERENCES))
                .andExpect(status().is3xxRedirection());
    }
}
