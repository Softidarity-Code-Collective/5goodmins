package com.fivegoodmins.fivegoodmins.action;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.AppUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@Import(TestcontainersConfiguration.class)
@DataJpaTest
class ActionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-11T09:00:00Z");

    @Autowired ActionRepository actions;
    @Autowired AppUserRepository users;
    @Autowired EntityManager em;

    private AppUser author(String email) {
        return users.save(new AppUser(email, "{bcrypt}x", "Georgi", NOW));
    }

    private Action action(AppUser author, String title) {
        return new Action(author, title, "opisanie", ActionType.PETITION,
                NOW.plus(30, ChronoUnit.DAYS));
    }

    @Test
    void roundTripsCategoriesAndRegionsThroughTheirJoinTables() {
        Action a = action(author("rt@seed.example"), "Spasi gorata nad Sofia");
        a.setCategories(Set.of(Category.NATURE, Category.LOCAL_COMMUNITIES));
        a.setRegions(Set.of(Region.SOFIA_CITY, Region.NATIONAL));
        actions.saveAndFlush(a);
        em.clear();   // force a real read, not the persistence context's copy

        Action loaded = actions.findById(a.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ActionStatus.PENDING);   // fails closed
        assertThat(loaded.getPublishedAt()).isNull();
        assertThat(loaded.getCategories())
                .containsExactlyInAnyOrder(Category.NATURE, Category.LOCAL_COMMUNITIES);
        assertThat(loaded.getRegions())
                .containsExactlyInAnyOrder(Region.SOFIA_CITY, Region.NATIONAL);
    }

    @Test
    void writesPublishedAtOnceAndNeverAgain() {
        Action a = action(author("pub@seed.example"), "Spasi gorata nad Sofia");
        a.publish(NOW);
        actions.saveAndFlush(a);

        a.reject();
        a.publish(NOW.plus(10, ChronoUnit.DAYS));   // an admin reverses the decision
        actions.saveAndFlush(a);

        // Re-stamping would mail the action to every subscriber again (ADR-005).
        assertThat(a.getPublishedAt()).isEqualTo(NOW.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void anyNumberOfLinklessActionsCoexistBecauseNullsAreDistinct() {
        AppUser author = author("linkless@seed.example");
        actions.saveAndFlush(action(author, "Prva akcia"));
        actions.saveAndFlush(action(author, "Vtora akcia"));

        assertThat(actions.count()).isEqualTo(2);
    }

    @Test
    void refusesASecondActionOnTheSameNormalizedUrlAndNamesTheConstraint() {
        AppUser author = author("dup@seed.example");
        Action first = action(author, "Prva akcia");
        first.setLink("https://example.org/p/1", "example.org/p/1");
        actions.saveAndFlush(first);

        // Same campaign, different spelling: www., tracking params, trailing slash.
        Action second = action(author, "Vtora akcia");
        second.setLink("https://www.EXAMPLE.org/p/1/?utm_source=fb", "example.org/p/1");

        // Identifying the constraint *by name* is what lets the service show
        // "this link is already on the platform" instead of a 500 (ADR-011).
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> actions.saveAndFlush(second))
                .withMessageContaining("uq_action_normalized_url");
    }

    @Test
    void countsEveryActionThatWasEverApprovedNotOnlyThoseStillPublished() {
        AppUser author = author("threshold@seed.example");

        Action live = action(author, "Prva akcia");
        live.publish(NOW);
        actions.saveAndFlush(live);

        Action withdrawnByItsPoster = action(author, "Vtora akcia");
        withdrawnByItsPoster.publish(NOW);
        withdrawnByItsPoster.withdraw();
        actions.saveAndFlush(withdrawnByItsPoster);

        Action takenDownByAnAdmin = action(author, "Treta akcia");
        takenDownByAnAdmin.publish(NOW);
        takenDownByAnAdmin.reject();
        actions.saveAndFlush(takenDownByAnAdmin);

        Action neverApproved = action(author, "Chetvarta akcia");
        actions.saveAndFlush(neverApproved);

        // All three were approved by an admin at some point, so the author has
        // cleared the POSTER floor. Counting status = PUBLISHED would say one.
        assertThat(actions.countByAuthorIdAndPublishedAtIsNotNull(author.getId()))
                .isEqualTo(3);
    }

    @Test
    void countsOnlyTodaysSubmissionsAgainstTheQuota() {
        AppUser author = author("quota@seed.example");
        actions.saveAndFlush(action(author, "Prva akcia"));
        actions.saveAndFlush(action(author, "Vtora akcia"));

        // created_at is Hibernate's clock, so the window is relative to real now.
        assertThat(actions.countByAuthorIdAndCreatedAtAfter(
                author.getId(), Instant.now().minus(1, ChronoUnit.DAYS))).isEqualTo(2);
        assertThat(actions.countByAuthorIdAndCreatedAtAfter(
                author.getId(), Instant.now().plus(1, ChronoUnit.MINUTES))).isZero();
    }

    @Test
    void refusesAWithdrawnActionThatWasNeverPublished() {
        // Unreachable through the entity's own API -- withdraw() throws unless
        // the action is PUBLISHED. The CHECK is the backstop for a future code
        // path that writes status without going through this class.
        Action a = action(author("backstop@seed.example"), "Peta akcia");
        actions.saveAndFlush(a);

        // Native SQL runs at once rather than at the next flush, which is
        // exactly the kind of code path the CHECK exists for. The exception is
        // JPA's, not Spring's DataIntegrityViolationException: translation into
        // the Spring DAO hierarchy happens at the repository proxy boundary, and
        // this call goes straight to the EntityManager.
        assertThatExceptionOfType(PersistenceException.class)
                .isThrownBy(() -> em.createNativeQuery(
                                "UPDATE action SET status = 'WITHDRAWN' WHERE id = :id")
                        .setParameter("id", a.getId())
                        .executeUpdate())
                .withMessageContaining("ck_action_published_at_required");
    }

    // -----------------------------------------------------------------------
    // findNewestActive -- what the welcome email shows (ADR-005), and the
    // query page 1 will grow from. Four things can go wrong in it and each has
    // a test: the status filter, the expiry filter, the ordering, and the cap.
    // -----------------------------------------------------------------------

    private Action published(AppUser author, String title, Instant publishedAt, Instant endsAt) {
        Action a = new Action(author, title, "opisanie", ActionType.PETITION, endsAt);
        a.publish(publishedAt);
        return actions.saveAndFlush(a);
    }

    /**
     * The status and expiry filters together.
     *
     * <p>The instants here are relative to real time rather than to this
     * class's fixed {@code NOW}, and that is not a style choice:
     * {@code created_at} is written by {@code @CreationTimestamp} from the
     * system clock, and {@code ck_action_ends_at_after_created} compares the
     * two -- so an already-expired row simply cannot be inserted. The database
     * refuses to hold the fixture this test wanted, which is the constraint
     * doing its job. The same question asked the other way round works: insert
     * a row that ends soon, then ask the query what is active slightly later.
     */
    @Test
    void listsOnlyPublishedActionsThatHaveNotEnded() {
        AppUser author = author("feed@seed.example");
        Instant base = Instant.now();
        Instant laterToday = base.plus(2, ChronoUnit.HOURS);

        published(author, "Zhiva akcia", base.minus(1, ChronoUnit.DAYS), base.plus(30, ChronoUnit.DAYS));

        // Published, and its end date falls between the insert and the query.
        // Expiry is derived in the query and never a stored status, so this row
        // is untouched and simply stops being returned.
        published(author, "Iztekla akcia", base.minus(40, ChronoUnit.DAYS), base.plus(1, ChronoUnit.HOURS));

        actions.saveAndFlush(action(author, "Chaka odobrenie"));            // PENDING

        Action rejected = action(author, "Otkazana akcia");
        rejected.publish(base.minus(2, ChronoUnit.DAYS));
        rejected.reject();
        actions.saveAndFlush(rejected);

        Action withdrawn = action(author, "Ottegleni akcia");
        withdrawn.publish(base.minus(3, ChronoUnit.DAYS));
        withdrawn.withdraw();
        actions.saveAndFlush(withdrawn);

        em.clear();

        assertThat(actions.findNewestActive(laterToday, ActionSummary.EXCERPT_CHARS, Limit.of(5)))
                .extracting(ActionSummary::title)
                .containsExactly("Zhiva akcia");
    }

    @Test
    void returnsProjectionsRatherThanEntities() {
        AppUser author = author("projection@seed.example");
        Action saved = published(author, "Spasi gorata nad Sofia",
                NOW.minus(1, ChronoUnit.DAYS), NOW.plus(30, ChronoUnit.DAYS));
        em.clear();

        // The shape matters as much as the content: four columns, no author and
        // no collections, so nothing downstream can lazily reach for one
        // outside a transaction (ADR-003, open-in-view=false).
        assertThat(actions.findNewestActive(NOW, ActionSummary.EXCERPT_CHARS, Limit.of(5)))
                .singleElement()
                .isEqualTo(new ActionSummary(
                        saved.getId(), "Spasi gorata nad Sofia", ActionType.PETITION, "opisanie"));
    }

    @Test
    void ordersNewestFirstByPublicationRatherThanByCreation() {
        AppUser author = author("order@seed.example");
        Instant ends = NOW.plus(30, ChronoUnit.DAYS);

        // Created in one order, published in another -- which is the ordinary
        // case, because an admin approves a queue in whatever order they like.
        published(author, "Podadena parva", NOW.minus(1, ChronoUnit.HOURS), ends);
        published(author, "Podadena vtora", NOW.minus(3, ChronoUnit.HOURS), ends);
        published(author, "Podadena treta", NOW.minus(2, ChronoUnit.HOURS), ends);
        em.clear();

        assertThat(actions.findNewestActive(NOW, ActionSummary.EXCERPT_CHARS, Limit.of(5)))
                .extracting(ActionSummary::title)
                .containsExactly("Podadena parva", "Podadena treta", "Podadena vtora");
    }

    /**
     * The tiebreaker, which is the half of the ORDER BY that looks like
     * decoration. Two actions approved in one click share a published_at to the
     * microsecond, and without "id desc" the database may return them in either
     * order -- so "the five newest" would differ between two identical requests
     * and the welcome email would be non-deterministic.
     */
    @Test
    void breaksATieOnIdSoTheOrderIsNotTheDatabasesChoice() {
        AppUser author = author("tie@seed.example");
        Instant sameMoment = NOW.minus(1, ChronoUnit.HOURS);
        Instant ends = NOW.plus(30, ChronoUnit.DAYS);

        Action first = published(author, "Parva", sameMoment, ends);
        Action second = published(author, "Vtora", sameMoment, ends);
        em.clear();

        assertThat(actions.findNewestActive(NOW, ActionSummary.EXCERPT_CHARS, Limit.of(5)))
                .extracting(ActionSummary::id)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void capsTheResultAtTheRequestedLimit() {
        AppUser author = author("limit@seed.example");
        Instant ends = NOW.plus(30, ChronoUnit.DAYS);
        for (int i = 1; i <= 8; i++) {
            published(author, "Akcia " + i, NOW.minus(i, ChronoUnit.HOURS), ends);
        }
        em.clear();

        List<ActionSummary> newest = actions.findNewestActive(NOW, ActionSummary.EXCERPT_CHARS, Limit.of(5));

        assertThat(newest).hasSize(5);
        assertThat(newest).first().extracting(ActionSummary::title).isEqualTo("Akcia 1");
    }

    /**
     * An empty site, which is what the first confirmed account on a fresh
     * deployment sees before the seed migration runs. The welcome email has a
     * line for it, so this must be an empty list rather than anything else.
     */
    @Test
    void returnsAnEmptyListWhenNothingIsPublished() {
        actions.saveAndFlush(action(author("empty@seed.example"), "Chaka odobrenie"));
        em.clear();

        assertThat(actions.findNewestActive(NOW, ActionSummary.EXCERPT_CHARS, Limit.of(5))).isEmpty();
    }

    /**
     * The projection carries an excerpt, not the column. A description is
     * {@code text} and may be 5,000 characters; reading all of it to display
     * 140 is affordable once per account verification and not once per
     * subscriber per day, which is the shape the digest will reuse.
     */
    @Test
    void cutsTheDescriptionToAnExcerptInTheQuery() {
        AppUser author = author("excerpt@seed.example");
        String paragraph = "Дълго описание. ".repeat(300);
        Action action = new Action(author, "Дълга акция", paragraph,
                ActionType.PETITION, NOW.plus(30, ChronoUnit.DAYS));
        action.publish(NOW.minus(1, ChronoUnit.HOURS));
        actions.saveAndFlush(action);
        em.clear();

        ActionSummary summary = actions.findNewestActive(NOW, ActionSummary.EXCERPT_CHARS, Limit.of(5)).getFirst();

        assertThat(paragraph.length()).as("the fixture is longer than the cut").isGreaterThan(1000);
        assertThat(summary.excerpt())
                .hasSize(ActionSummary.EXCERPT_CHARS)
                .isEqualTo(paragraph.substring(0, ActionSummary.EXCERPT_CHARS));
    }

    @Test
    void leavesAShortDescriptionWhole() {
        AppUser author = author("short@seed.example");
        published(author, "Кратка акция", NOW.minus(1, ChronoUnit.HOURS), NOW.plus(30, ChronoUnit.DAYS));
        em.clear();

        assertThat(actions.findNewestActive(NOW, ActionSummary.EXCERPT_CHARS, Limit.of(5)).getFirst().excerpt())
                .isEqualTo("opisanie");
    }
}
