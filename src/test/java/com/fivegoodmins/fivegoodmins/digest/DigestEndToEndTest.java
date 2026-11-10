package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.action.Action;
import com.fivegoodmins.fivegoodmins.action.ActionRepository;
import com.fivegoodmins.fivegoodmins.action.ActionType;
import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import com.fivegoodmins.fivegoodmins.shared.email.EmailMessage;
import com.fivegoodmins.fivegoodmins.shared.email.EmailSender;
import com.fivegoodmins.fivegoodmins.subscription.SubscriptionService;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * One digest, all the way through, against a real PostgreSQL and the real
 * wiring.
 *
 * <p><strong>This test exists because the feature shipped a bug that every
 * other test in it was structurally unable to see.</strong>
 * {@code advanceWatermark} is a {@code @Modifying} query and
 * {@code DigestService} deliberately holds no transaction, so in production the
 * statement threw <em>after</em> the email had already gone out: the digest was
 * delivered, the watermark stayed put, and the same actions would have been
 * mailed again the next morning and every morning after. A duplicate digest for
 * every subscriber, daily, forever -- the precise failure the watermark pattern
 * exists to prevent.
 *
 * <p>It was invisible because {@code DigestServiceTest} mocks the repository,
 * {@code DigestQueriesTest} never calls it, and <strong>every other
 * {@code @SpringBootTest} in this suite is {@code @Transactional}</strong>, so a
 * transaction was always already open and the missing one was supplied by the
 * test. It appeared on the first real run against Mailpit.
 *
 * <p><strong>Hence no {@code @Transactional} here, and that is the whole point
 * of the file rather than an omission.</strong> This class runs the job the way
 * the scheduler does -- no ambient transaction, each step taking its own -- and
 * is therefore the only test that can catch this class of defect. Do not add
 * the annotation to make the cleanup simpler; {@link #removeWhatThisTestWrote()}
 * is the cost of keeping it honest.
 *
 * <p>The only thing faked is the transport. ADR-005 is explicit that no test
 * points at Mailpit, so {@link EmailSender} is a mock that records what it was
 * asked to send -- which is also how the assertions read the rendered email,
 * both parts of it, as a real recipient would.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DigestEndToEndTest {

    @Autowired DigestService digests;
    @Autowired AppUserRepository users;
    @Autowired ActionRepository actions;
    @Autowired SubscriptionService subscriptions;

    /**
     * Records rather than sends. A {@code @MockitoBean} replaces the sender in
     * the real context, so everything in front of it -- the composer, both
     * templates, the message bundles, the locale -- is the production path.
     */
    @MockitoBean
    EmailSender sender;

    private final List<EmailMessage> delivered = new ArrayList<>();

    private AppUser reader;
    private AppUser author;
    private final List<Long> writtenActions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        delivered.clear();
        doAnswer(call -> {
            delivered.add(call.getArgument(0));
            return null;
        }).when(sender).send(any());

        Instant now = Instant.now();

        author = users.saveAndFlush(
                new AppUser("e2e-author@seed.example", "{bcrypt}x", "Автор", now));

        // A verified, enabled, subscribed reader whose watermark is a year back,
        // so the actions below are above it. Registration sets the watermark to
        // `now` by design (ADR-005), which is why this has to be moved.
        AppUser subscriber = new AppUser("e2e-reader@seed.example", "{bcrypt}x", "Георги", now);
        subscriber.markEmailVerified(now);
        subscriber.setLastDigestSentAt(now.minus(365, ChronoUnit.DAYS));
        reader = users.saveAndFlush(subscriber);
    }

    /**
     * Deletes what this test wrote, because there is no transaction to roll
     * back and the PostgreSQL container is shared with every other suite
     * (ADR-014). Actions first: the FK to {@code app_user} is
     * {@code ON DELETE CASCADE}, so deleting the users would take them anyway,
     * but doing it explicitly keeps the order obvious.
     */
    @AfterEach
    void removeWhatThisTestWrote() {
        writtenActions.forEach(actions::deleteById);
        writtenActions.clear();
        users.deleteById(reader.getId());
        users.deleteById(author.getId());
    }

    private Action publish(String title, Set<Category> categories, Set<Region> regions) {
        Action action = new Action(author, title, "Опишете накратко за какво става дума.",
                ActionType.PETITION, Instant.now().plus(30, ChronoUnit.DAYS));
        action.setCategories(categories);
        action.setRegions(regions);
        // An hour ago, so it is outside the five-minute lag window.
        action.publish(Instant.now().minus(1, ChronoUnit.HOURS));
        Action saved = actions.saveAndFlush(action);
        writtenActions.add(saved.getId());
        return saved;
    }

    private Instant watermarkOf(AppUser user) {
        return users.findById(user.getId()).orElseThrow().getLastDigestSentAt();
    }

    /**
     * The one digest delivered to this test's reader.
     *
     * <p><strong>Every assertion in this file is scoped to this reader rather
     * than to {@link DigestRun}'s totals, and that is deliberate.</strong> The
     * PostgreSQL container is shared by the whole suite and this test has no
     * transaction to roll back, so any verified, subscribed account another
     * suite has committed is also a subscriber on this run. Asserting
     * {@code run.sent() == 1} would make this file fail for reasons that have
     * nothing to do with it -- the same trap {@code application.properties}
     * avoids by dropping the demo seed. What this test owns is its own reader.
     */
    private EmailMessage digestTo(String recipient) {
        List<EmailMessage> mine = delivered.stream().filter(m -> m.to().equals(recipient)).toList();
        assertThat(mine).as("digests sent to %s", recipient).hasSize(1);
        return mine.getFirst();
    }

    /** Nothing was delivered to this test's reader. */
    private void assertNothingDeliveredToTheReader() {
        assertThat(delivered).extracting(EmailMessage::to).doesNotContain(reader.getEmail());
    }

    // ------------------------------------------------------------------
    // The regression
    // ------------------------------------------------------------------

    @Test
    void movesTheWatermarkWithNoAmbientTransaction() {
        publish("Спаси гората над Варна", Set.of(Category.NATURE), Set.of(Region.VARNA));
        Instant before = watermarkOf(reader);

        digests.sendDailyDigest();

        // THE assertion this file was written for. Before @Transactional was
        // put on advanceWatermark, the email was delivered exactly like this
        // and the watermark below was unchanged -- so the next run would have
        // sent the same actions again, and the one after that, forever.
        digestTo(reader.getEmail());
        assertThat(watermarkOf(reader)).isAfter(before);
    }

    @Test
    void sendsNothingOnASecondRunBecauseTheWatermarkMoved() {
        publish("Спаси гората над Варна", Set.of(Category.NATURE), Set.of(Region.VARNA));

        digests.sendDailyDigest();
        digestTo(reader.getEmail());
        delivered.clear();

        // The whole point of the watermark, end to end: nothing new for this
        // reader, so no second email. This is the assertion that fails with the
        // original bug even if the first one somehow passed.
        digests.sendDailyDigest();
        assertNothingDeliveredToTheReader();
    }

    // ------------------------------------------------------------------
    // What actually arrives
    // ------------------------------------------------------------------

    @Test
    void deliversADigestWithBothPartsRendered() {
        publish("Спаси гората над Варна", Set.of(Category.NATURE), Set.of(Region.VARNA));

        digests.sendDailyDigest();

        EmailMessage digest = digestTo("e2e-reader@seed.example");

        // The subject carries the count, so a month of digests does not thread
        // together in Gmail -- and with ONE action it has to say so in
        // Bulgarian. The assertion this replaced was
        // contains("1").contains("нови действия"), which is exactly the
        // assertion that passes on "1 нови действия": it checked that both
        // halves were present and never that they agreed.
        assertThat(digest.subject()).isEqualTo("1 ново действие за вас");

        // Both bodies, every time: a client that renders only text otherwise
        // shows nothing, and HTML-only mail scores worse with spam filters --
        // which is the sending reputation every later digest depends on.
        assertThat(digest.html())
                .contains("Спаси гората над Варна")
                .contains("Петиция")        // action.type.PETITION from the bundle
                .contains("Природа")        // category.NATURE
                .contains("Варна");         // region.VARNA
        assertThat(digest.text())
                .contains("Спаси гората над Варна")
                .contains("Природа")
                .contains("Варна");
    }

    @Test
    void agreesInNumberForASingleActionDigest() {
        publish("Спаси гората над Варна", Set.of(Category.NATURE), Set.of(Region.VARNA));

        digests.sendDailyDigest();
        EmailMessage digest = digestTo(reader.getEmail());

        // One action, singular everywhere: the subject, and the intro's noun
        // AND its relative clause and verb, which Bulgarian inflects together
        // ("което отговаря", not "които отговарят"). A plural-only string would
        // read "1 нови действия, които отговарят" -- wrong three times in one
        // sentence, in the commonest digest there is.
        assertThat(digest.subject()).isEqualTo("1 ново действие за вас");
        assertThat(digest.html())
                .contains("1 ново действие, което отговаря")
                .doesNotContain("нови действия");
        assertThat(digest.text())
                .contains("1 ново действие, което отговаря")
                .doesNotContain("нови действия");
    }

    @Test
    void agreesInNumberForAMultipleActionDigest() {
        publish("Спаси гората над Варна", Set.of(Category.NATURE), Set.of(Region.VARNA));
        publish("Безопасни пътеки в Пловдив", Set.of(Category.CHILDREN), Set.of(Region.PLOVDIV));

        digests.sendDailyDigest();
        EmailMessage digest = digestTo(reader.getEmail());

        // The other branch of the same choice, so the test pins both and not
        // whichever one the fixture happened to produce.
        assertThat(digest.subject()).isEqualTo("2 нови действия за вас");
        assertThat(digest.html())
                .contains("2 нови действия, които отговарят")
                .doesNotContain("ново действие");
    }

    @Test
    void carriesAnUnsubscribeLinkInBothParts() {
        publish("Спаси гората над Варна", Set.of(Category.NATURE), Set.of(Region.VARNA));

        digests.sendDailyDigest();
        EmailMessage digest = digestTo("e2e-reader@seed.example");

        // The legal requirement (ADR-005), and the only one of the four emails
        // that has it. In the plain-text part too: a reader whose client shows
        // text has the same right to opt out.
        assertThat(digest.html()).contains("/unsubscribe?token=");
        assertThat(digest.text()).contains("/unsubscribe?token=");
    }

    @Test
    void rendersNoBadgeOrLabelAsARawEnumConstant() {
        publish("Спаси гората над Варна",
                Set.of(Category.LOCAL_COMMUNITIES), Set.of(Region.SOFIA_CITY));

        digests.sendDailyDigest();
        EmailMessage digest = digestTo("e2e-reader@seed.example");

        // With locale fallback off (MessageSourceConfig) a missing key is a
        // NoSuchMessageException rather than a silent gap, so this is really
        // checking that nothing bypassed the bundle with a toString().
        assertThat(digest.html())
                .doesNotContain("LOCAL_COMMUNITIES")
                .doesNotContain("SOFIA_CITY")
                .doesNotContain("PETITION");
        assertThat(digest.html()).contains("Местни общности").contains("София (столица)");
    }

    // ------------------------------------------------------------------
    // Who is left out
    // ------------------------------------------------------------------

    @Test
    void sendsNothingWhenTheActionMatchesNothingTheReaderFollows() {
        subscriptions.save(reader.getId(), Set.of(Category.ANIMALS), Set.of(Region.BURGAS), true);
        publish("Спаси гората над Варна", Set.of(Category.NATURE), Set.of(Region.VARNA));

        Instant before = watermarkOf(reader);
        digests.sendDailyDigest();

        assertNothingDeliveredToTheReader();
        // No send, so no advance -- which is what lets this reader still receive
        // the action if they later follow NATURE.
        assertThat(watermarkOf(reader)).isEqualTo(before);
    }

    @Test
    void skipsAnUnsubscribedReaderEntirely() {
        subscriptions.save(reader.getId(), Set.of(), Set.of(), false);
        publish("Спаси гората над Варна", Set.of(Category.NATURE), Set.of(Region.VARNA));

        Instant before = watermarkOf(reader);
        digests.sendDailyDigest();

        // Not "sent an empty digest": the account is not a subscriber at all,
        // so findSubscribers never returns it and it never reaches the query.
        assertNothingDeliveredToTheReader();
        assertThat(watermarkOf(reader)).isEqualTo(before);
    }
}
