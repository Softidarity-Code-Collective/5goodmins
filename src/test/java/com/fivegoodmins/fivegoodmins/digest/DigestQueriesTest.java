package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.action.Action;
import com.fivegoodmins.fivegoodmins.action.ActionRepository;
import com.fivegoodmins.fivegoodmins.action.ActionSummary;
import com.fivegoodmins.fivegoodmins.action.ActionType;
import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import com.fivegoodmins.fivegoodmins.subscription.SubscriptionFilter;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The digest's matching query against a real PostgreSQL (ADR-014), clause by
 * clause.
 *
 * <p><strong>This is the test that matters most in the feature.</strong> Every
 * clause in {@code DigestActionRepository.findMatching} exists to prevent one
 * specific wrong email -- an action sent twice, an expired action sent at all,
 * an action nobody is ever told about -- and none of those is visible in a unit
 * test against a mocked repository, because the bug would be in the SQL.
 * ADR-005 calls the watermark pattern the reusable idea in this design; this is
 * what holds it to its claims.
 *
 * <p>{@code @DataJpaTest}, so each test is one transaction that is rolled back.
 * The container is shared with every other suite, hence the {@code seed.example}
 * addresses and the fact that nothing here counts rows globally -- each
 * assertion names the actions it created.
 */
/*
 * DigestQueries is imported explicitly because @DataJpaTest is a slice: it
 * registers entities and Spring Data repositories and nothing else, so a
 * @Service in the same package is not in the context. Importing the one
 * collaborator is cheaper than widening this to a @SpringBootTest, which would
 * also start the web layer, the scheduler and the mail wiring in order to test
 * three queries.
 */
@Import({TestcontainersConfiguration.class, DigestQueries.class})
@DataJpaTest
class DigestQueriesTest {

    /**
     * A fixed "now", so every boundary below is arithmetic rather than a race
     * against the real clock (ADR-019).
     */
    private static final Instant NOW = Instant.parse("2026-11-05T07:00:00Z");

    /**
     * The lag window, mirrored from {@code DigestService} rather than imported.
     * The constant there is private, and reading it would make a wrong constant
     * agree with itself: these tests assert the behaviour five minutes
     * produces.
     */
    private static final Instant CUTOFF = NOW.minus(5, ChronoUnit.MINUTES);

    /** Far enough back that everything published here is above it. */
    private static final Instant OLD_WATERMARK = NOW.minus(30, ChronoUnit.DAYS);

    /** The effective filter of a subscriber who has chosen nothing. */
    private static final SubscriptionFilter EVERYTHING = new SubscriptionFilter(
            EnumSet.allOf(Category.class), EnumSet.allOf(Region.class));

    @Autowired DigestQueries digests;
    @Autowired ActionRepository actions;
    @Autowired AppUserRepository users;

    private AppUser author;

    /**
     * A fresh author per test, not a lazily cached field: each test method is
     * its own transaction and is rolled back, so an {@code AppUser} held across
     * methods would carry an id whose row no longer exists.
     */
    @BeforeEach
    void createAuthor() {
        author = users.save(new AppUser("digest-author@seed.example", "{bcrypt}x", "Avtor", NOW));
    }

    private Action published(String title, Instant publishedAt, Instant endsAt,
                             Set<Category> categories, Set<Region> regions) {
        Action action = new Action(author, title, "Opisanie na deistvieto.",
                ActionType.PETITION, endsAt);
        action.setCategories(categories);
        action.setRegions(regions);
        action.publish(publishedAt);
        return actions.saveAndFlush(action);
    }

    private Action published(String title, Instant publishedAt) {
        return published(title, publishedAt, NOW.plus(30, ChronoUnit.DAYS),
                Set.of(Category.NATURE), Set.of(Region.VARNA));
    }

    private List<DigestAction> digestFrom(Instant watermark) {
        return digests.digestFor(watermark, CUTOFF, NOW, EVERYTHING);
    }

    private List<DigestAction> digestMatching(Set<Category> categories, Set<Region> regions) {
        return digests.digestFor(OLD_WATERMARK, CUTOFF, NOW,
                new SubscriptionFilter(categories, regions));
    }

    // ------------------------------------------------------------------
    // The watermark bounds
    // ------------------------------------------------------------------

    @Test
    void includesWhatWasPublishedAfterTheWatermark() {
        Action fresh = published("Spasi gorata nad Varna", NOW.minus(2, ChronoUnit.HOURS));

        assertThat(digestFrom(OLD_WATERMARK))
                .extracting(DigestAction::id)
                .contains(fresh.getId());
    }

    @Test
    void excludesWhatTheWatermarkHasAlreadyPassed() {
        Instant publishedAt = NOW.minus(10, ChronoUnit.DAYS);
        Action alreadySent = published("Veche izprateno deistvie", publishedAt);

        assertThat(digestFrom(publishedAt.plus(1, ChronoUnit.DAYS)))
                .extracting(DigestAction::id)
                .doesNotContain(alreadySent.getId());
    }

    @Test
    void excludesTheActionSittingExactlyOnTheWatermark() {
        // The bound is strictly greater, which is what stops the action that
        // moved the watermark last night from being sent again tonight. An
        // off-by-one here is a duplicate in every subscriber's inbox.
        Action onTheBoundary = published("Deistvie na granitsata", NOW.minus(1, ChronoUnit.DAYS));

        // Read the stored value back rather than assuming the Instant survived
        // unchanged: timestamptz keeps microseconds, Instant keeps nanoseconds.
        Instant watermark = actions.findById(onTheBoundary.getId()).orElseThrow().getPublishedAt();

        assertThat(digestFrom(watermark))
                .extracting(DigestAction::id)
                .doesNotContain(onTheBoundary.getId());
    }

    // ------------------------------------------------------------------
    // The lag window
    // ------------------------------------------------------------------

    @Test
    void excludesWhatWasPublishedInsideTheLagWindow() {
        // Two minutes ago: above the watermark, but inside the five minutes the
        // job deliberately does not look at, because a publish transaction that
        // began before the job started may not have committed yet. Sending this
        // and advancing the watermark past it is what would make a slightly
        // later commit invisible to everybody, forever.
        Action tooRecent = published("Prekaleno novo deistvie", NOW.minus(2, ChronoUnit.MINUTES));

        assertThat(digestFrom(OLD_WATERMARK))
                .extracting(DigestAction::id)
                .doesNotContain(tooRecent.getId());
    }

    @Test
    void includesWhatWasPublishedJustBeforeTheLagWindow() {
        // The other side of the same boundary, so the window is shown to have
        // an edge rather than swallowing everything recent.
        Action justOldEnough = published("Tochno navreme", NOW.minus(6, ChronoUnit.MINUTES));

        assertThat(digestFrom(OLD_WATERMARK))
                .extracting(DigestAction::id)
                .contains(justOldEnough.getId());
    }

    // ------------------------------------------------------------------
    // Status and expiry
    // ------------------------------------------------------------------

    @Test
    void excludesAnythingNotCurrentlyPublished() {
        // Both were published once, so published_at is set on both and is above
        // the watermark. The digest has to read the current status, not the
        // timestamp -- the state graph is cyclic (ADR-016).
        Action withdrawn = published("Ottegleno deistvie", NOW.minus(1, ChronoUnit.HOURS));
        withdrawn.withdraw();
        actions.saveAndFlush(withdrawn);

        Action rejected = published("Othvarleno deistvie", NOW.minus(1, ChronoUnit.HOURS));
        rejected.reject();
        actions.saveAndFlush(rejected);

        assertThat(digestFrom(OLD_WATERMARK))
                .extracting(DigestAction::id)
                .doesNotContain(withdrawn.getId(), rejected.getId());
    }

    @Test
    void excludesAnActionOnceItHasExpired() {
        // Expiry is derived from ends_at per query, never a stored status, so
        // this clause is what keeps a dead petition out of an email.
        //
        // Tested by moving `now` forward rather than by backdating ends_at, and
        // that is forced rather than stylistic: an already-expired action
        // cannot be INSERTed at all. ck_action_ends_at_after_created compares
        // ends_at against created_at, and created_at is Hibernate's
        // @CreationTimestamp -- the real clock, which no test controls
        // (ADR-019's two stated exceptions). So the row is created live and the
        // question is asked on a later day.
        Action endsIn30Days = published("Deistvie s krai",
                NOW.minus(1, ChronoUnit.HOURS),
                NOW.plus(30, ChronoUnit.DAYS),
                Set.of(Category.NATURE), Set.of(Region.VARNA));

        // Before it ends: in the digest. Asserted so that the exclusion below
        // is the clause doing the work and not a fixture that never matched.
        assertThat(digestFrom(OLD_WATERMARK))
                .extracting(DigestAction::id)
                .contains(endsIn30Days.getId());

        Instant afterItEnds = NOW.plus(60, ChronoUnit.DAYS);
        List<DigestAction> later = digests.digestFor(
                OLD_WATERMARK, afterItEnds.minus(5, ChronoUnit.MINUTES), afterItEnds, EVERYTHING);

        assertThat(later).extracting(DigestAction::id).doesNotContain(endsIn30Days.getId());
    }

    // ------------------------------------------------------------------
    // Matching, and the duplication EXISTS prevents
    // ------------------------------------------------------------------

    @Test
    void returnsAMultiplyTaggedActionExactlyOnce() {
        // ADR-005's reason for EXISTS over a join: an action tagged BURGAS and
        // NATIONAL would come back twice for a subscriber who follows both, and
        // the badge queries reintroduce exactly that risk. This assertion is
        // what fails if either ever becomes a join.
        Action multi = published("Deistvie v dva raiona",
                NOW.minus(1, ChronoUnit.HOURS),
                NOW.plus(30, ChronoUnit.DAYS),
                Set.of(Category.NATURE, Category.ANIMALS),
                Set.of(Region.BURGAS, Region.NATIONAL));

        List<DigestAction> digest = digestMatching(
                Set.of(Category.NATURE, Category.ANIMALS),
                Set.of(Region.BURGAS, Region.NATIONAL));

        assertThat(digest).filteredOn(a -> a.id().equals(multi.getId())).hasSize(1);
    }

    @Test
    void requiresBothACategoryAndARegionToMatch() {
        Action natureInVarna = published("Priroda vav Varna",
                NOW.minus(1, ChronoUnit.HOURS),
                NOW.plus(30, ChronoUnit.DAYS),
                Set.of(Category.NATURE), Set.of(Region.VARNA));

        // The right category, the wrong region. AND, not OR: an OR here would
        // mail every nature action in the country to somebody who asked only
        // about Varna.
        assertThat(digestMatching(Set.of(Category.NATURE), Set.of(Region.BURGAS)))
                .extracting(DigestAction::id)
                .doesNotContain(natureInVarna.getId());

        // The right region, the wrong category.
        assertThat(digestMatching(Set.of(Category.POLITICAL), Set.of(Region.VARNA)))
                .extracting(DigestAction::id)
                .doesNotContain(natureInVarna.getId());

        // Both right, so it is only the conjunction that excluded it above.
        assertThat(digestMatching(Set.of(Category.NATURE), Set.of(Region.VARNA)))
                .extracting(DigestAction::id)
                .contains(natureInVarna.getId());
    }

    @Test
    void matchesAnActionOnOneOfItsSeveralTags() {
        // The subscriber follows one of the action's two regions. EXISTS
        // short-circuits on the first match, which is the behaviour here.
        Action multi = published("Deistvie v dva raiona",
                NOW.minus(1, ChronoUnit.HOURS),
                NOW.plus(30, ChronoUnit.DAYS),
                Set.of(Category.NATURE), Set.of(Region.BURGAS, Region.NATIONAL));

        assertThat(digestMatching(Set.of(Category.NATURE), Set.of(Region.NATIONAL)))
                .extracting(DigestAction::id)
                .contains(multi.getId());
    }

    // ------------------------------------------------------------------
    // What the email renders
    // ------------------------------------------------------------------

    @Test
    void carriesTheBadgesTheEmailRenders() {
        Action tagged = published("Deistvie s etiketi",
                NOW.minus(1, ChronoUnit.HOURS),
                NOW.plus(30, ChronoUnit.DAYS),
                Set.of(Category.NATURE, Category.ANIMALS),
                Set.of(Region.VARNA, Region.NATIONAL));

        DigestAction rendered = only(digestFrom(OLD_WATERMARK), tagged.getId());

        // The grouping step: three queries in, one object per action out, with
        // every tag attached to the right one.
        assertThat(rendered.categories())
                .containsExactlyInAnyOrder(Category.NATURE, Category.ANIMALS);
        assertThat(rendered.regions())
                .containsExactlyInAnyOrder(Region.VARNA, Region.NATIONAL);
    }

    @Test
    void attachesEachActionsBadgesToThatActionAndNoOther() {
        // The grouping bug this guards against -- one tag set reused for every
        // row -- is invisible in a test with a single action in the digest.
        Action nature = published("Samo priroda",
                NOW.minus(2, ChronoUnit.HOURS), NOW.plus(30, ChronoUnit.DAYS),
                Set.of(Category.NATURE), Set.of(Region.VARNA));
        Action political = published("Samo politika",
                NOW.minus(1, ChronoUnit.HOURS), NOW.plus(30, ChronoUnit.DAYS),
                Set.of(Category.POLITICAL), Set.of(Region.BURGAS));

        List<DigestAction> digest = digestFrom(OLD_WATERMARK);

        assertThat(only(digest, nature.getId()).categories()).containsExactly(Category.NATURE);
        assertThat(only(digest, nature.getId()).regions()).containsExactly(Region.VARNA);
        assertThat(only(digest, political.getId()).categories()).containsExactly(Category.POLITICAL);
        assertThat(only(digest, political.getId()).regions()).containsExactly(Region.BURGAS);
    }

    @Test
    void ordersOldestFirst() {
        Action older = published("Po-staro", NOW.minus(3, ChronoUnit.HOURS));
        Action newer = published("Po-novo", NOW.minus(1, ChronoUnit.HOURS));

        List<Long> ids = digestFrom(OLD_WATERMARK).stream().map(DigestAction::id).toList();

        assertThat(ids).containsSubsequence(older.getId(), newer.getId());
    }

    @Test
    void cutsTheExcerptInSqlRatherThanReadingTheWholeDescription() {
        Action longDescription = new Action(author, "Dalgo opisanie",
                "x".repeat(5000), ActionType.PETITION, NOW.plus(30, ChronoUnit.DAYS));
        longDescription.setCategories(Set.of(Category.NATURE));
        longDescription.setRegions(Set.of(Region.VARNA));
        longDescription.publish(NOW.minus(1, ChronoUnit.HOURS));
        actions.saveAndFlush(longDescription);

        // The point of the bound: one email per subscriber per day must not
        // each read 5,000 characters in order to display 140 (ADR-005).
        assertThat(only(digestFrom(OLD_WATERMARK), longDescription.getId()).excerpt())
                .hasSize(ActionSummary.EXCERPT_CHARS);
    }

    @Test
    void returnsNothingWhenThereIsNothingNew() {
        published("Nishto novo", NOW.minus(1, ChronoUnit.HOURS));

        // A watermark at `now` is above everything, including the lag window.
        // This is the common case on most nights for most subscribers, and the
        // signal DigestService uses to send no email at all -- it also proves
        // the badge queries are skipped rather than issued with an empty IN.
        assertThat(digestFrom(NOW)).isEmpty();
    }

    /** The one entry for an id, asserting there is exactly one. */
    private static DigestAction only(List<DigestAction> digest, Long id) {
        List<DigestAction> matches = digest.stream().filter(a -> a.id().equals(id)).toList();
        assertThat(matches).as("entries for action %s", id).hasSize(1);
        return matches.getFirst();
    }
}
