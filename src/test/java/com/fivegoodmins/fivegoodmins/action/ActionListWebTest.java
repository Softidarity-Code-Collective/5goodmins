package com.fivegoodmins.fivegoodmins.action;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.AppUserRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The action listing, which exists as a structural placeholder because the
 * welcome email links to it (ADR-005).
 *
 * <p><strong>The first test is the one that justifies the file.</strong> Every
 * other path in this application so far is behind
 * {@code .anyRequest().authenticated()}, and the whole point of this page
 * landing in this change is that a person who has just confirmed their address
 * -- and is therefore not signed in -- can follow the link in their welcome
 * email and see actions rather than a login form. That was the review finding;
 * this is the assertion that keeps it fixed.
 *
 * <p>{@code @SpringBootTest} rather than {@code @WebMvcTest}, because the
 * latter loads neither {@code MessageSourceConfig} nor {@code LocaleConfig} and
 * every Bulgarian assertion below would fail for the wrong reason. The locale
 * is bound on {@code defaultRequest} for the same class of reason: MockMvc
 * sends {@code Accept-Language: en}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ActionListWebTest {

    private static final Locale BULGARIAN = Locale.forLanguageTag("bg");

    MockMvc mockMvc;

    @Autowired
    ActionRepository actions;

    @Autowired
    AppUserRepository users;

    @BeforeEach
    void buildMockMvc(@Autowired WebApplicationContext context) {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").locale(BULGARIAN))
                .build();
    }

    private AppUser poster() {
        return users.saveAndFlush(
                new AppUser("poster@seed.example", "{bcrypt}x", "Постващ", Instant.now()));
    }

    private void publish(AppUser author, String title, String description) {
        Action action = new Action(author, title, description, ActionType.PETITION,
                Instant.now().plus(30, ChronoUnit.DAYS));
        action.publish(Instant.now().minus(1, ChronoUnit.HOURS));
        actions.saveAndFlush(action);
    }

    @Test
    void isReadableWithoutAnAccount() throws Exception {
        publish(poster(), "Спаси гората над София", "Подпиши петицията.");

        mockMvc.perform(get(ActionPaths.LIST))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Спаси гората над София")));
    }

    @Test
    void labelsTheActionTypeFromTheMessageBundle() throws Exception {
        publish(poster(), "Спаси гората над София", "Подпиши петицията.");

        mockMvc.perform(get(ActionPaths.LIST))
                .andExpect(content().string(containsString("Петиция")));
    }

    /**
     * A fresh deployment has nothing published until the seed migration runs,
     * and a page with a heading and nothing under it reads as broken.
     */
    @Test
    void saysSoWhenThereIsNothingToShow() throws Exception {
        mockMvc.perform(get(ActionPaths.LIST))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("В момента няма активни действия")));
    }

    @Test
    void leavesOutActionsNobodyCanActOnYet() throws Exception {
        AppUser author = poster();
        publish(author, "Публикувана акция", "Подпиши.");
        actions.saveAndFlush(new Action(author, "Чака одобрение", "Подпиши.",
                ActionType.PETITION, Instant.now().plus(30, ChronoUnit.DAYS)));

        mockMvc.perform(get(ActionPaths.LIST))
                .andExpect(content().string(containsString("Публикувана акция")))
                .andExpect(content().string(not(containsString("Чака одобрение"))));
    }

    /**
     * The excerpt is cut twice on purpose -- to 200 characters by the query and
     * to 160 here -- and the page must show the second number, not the column.
     */
    @Test
    void showsAnExcerptRatherThanAWholeDescription() throws Exception {
        String paragraph = "Много дълго описание. ".repeat(60);
        publish(poster(), "Дълга акция", paragraph);

        mockMvc.perform(get(ActionPaths.LIST))
                .andExpect(content().string(not(containsString(paragraph))))
                .andExpect(content().string(containsString("Много дълго описание.")));
    }
}
