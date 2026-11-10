package com.fivegoodmins.fivegoodmins.digest;

import com.fivegoodmins.fivegoodmins.action.ActionType;
import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import com.fivegoodmins.fivegoodmins.shared.email.EmailComposer;
import com.fivegoodmins.fivegoodmins.shared.email.EmailDeliveryException;
import com.fivegoodmins.fivegoodmins.shared.email.EmailMessage;
import com.fivegoodmins.fivegoodmins.shared.email.EmailProperties;
import com.fivegoodmins.fivegoodmins.shared.email.EmailSender;
import com.fivegoodmins.fivegoodmins.subscription.SubscriptionFilter;
import com.fivegoodmins.fivegoodmins.subscription.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The watermark rule, with a fixed {@link Clock} and no database.
 *
 * <p><strong>The one thing this file is for: the watermark moves only after a
 * send succeeds.</strong> That ordering is ADR-005's central claim and the
 * reason the digest needs no queue, no retry table and no per-user
 * notification rows -- and it is invisible to {@code DigestQueriesTest}, which
 * tests the SQL, and to a {@code @SpringBootTest}, which cannot easily make a
 * mail provider fail. Mocks are the right tool precisely because the
 * interesting cases are "the provider threw" and "nothing matched".
 *
 * <p>The clock is injected and fixed, which is ADR-019's whole argument in one
 * line: the cutoff is {@code now} minus five minutes, and asserting on it
 * requires knowing what {@code now} was. With {@code Instant.now()} inside the
 * service there would be nothing to assert against.
 *
 * <p>No Spring context. These are seven collaborators and one loop.
 */
class DigestServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T05:00:00Z");

    /** What the service must advance the watermark to: never {@code NOW}. */
    private static final Instant CUTOFF = NOW.minus(Duration.ofMinutes(5));

    private static final Instant WATERMARK = NOW.minus(Duration.ofDays(1));

    private DigestSubscriberRepository subscribers;
    private DigestQueries digests;
    private SubscriptionService subscriptions;
    private EmailComposer composer;
    private EmailSender sender;
    private DigestService service;

    @BeforeEach
    void setUp() {
        subscribers = mock(DigestSubscriberRepository.class);
        digests = mock(DigestQueries.class);
        subscriptions = mock(SubscriptionService.class);
        composer = mock(EmailComposer.class);
        sender = mock(EmailSender.class);

        // A real EmailProperties rather than a mock: it is a record, so it is
        // final, and constructing one is shorter than persuading Mockito to
        // mock it. The values only have to be recognisably not production.
        EmailProperties properties = new EmailProperties(
                "no-reply@test.invalid", "5 Good Minutes (test)", "https://test.invalid",
                new EmailProperties.Brevo(null, Duration.ofSeconds(5), Duration.ofSeconds(10)));

        when(subscriptions.effectiveFilterFor(anyLong())).thenReturn(
                new SubscriptionFilter(EnumSet.allOf(Category.class), EnumSet.allOf(Region.class)));
        when(subscriptions.unsubscribeToken(anyLong())).thenReturn("1.9999999999.mac");
        // Echoes the recipient rather than returning one fixed message, so a
        // test can fail the send for exactly one subscriber in a run.
        when(composer.compose(anyString(), any(), any(), any(), any()))
                .thenAnswer(call -> new EmailMessage(
                        call.getArgument(0), "Subject", "<p>html</p>", "text"));
        when(subscribers.advanceWatermark(anyLong(), any())).thenReturn(1);

        service = new DigestService(subscribers, digests, subscriptions, composer, sender,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DigestSubscriber subscriber(long id, String locale) {
        return new DigestSubscriber(id, "reader" + id + "@test.invalid", "Chitatel", locale, WATERMARK);
    }

    private static DigestAction anAction() {
        return new DigestAction(1L, "Spasi gorata", ActionType.PETITION, "Opisanie",
                Set.of(Category.NATURE), Set.of(Region.VARNA));
    }

    private void given(List<DigestSubscriber> due) {
        when(subscribers.findSubscribers()).thenReturn(due);
    }

    private void withSomethingNew() {
        when(digests.digestFor(any(), any(), any(), any())).thenReturn(List.of(anAction()));
    }

    private void withNothingNew() {
        when(digests.digestFor(any(), any(), any(), any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // The watermark, which is the whole point
    // ------------------------------------------------------------------

    @Test
    void movesTheWatermarkAfterASuccessfulSend() {
        given(List.of(subscriber(7, "bg")));
        withSomethingNew();

        DigestRun run = service.sendDailyDigest();

        verify(sender).send(any());
        verify(subscribers).advanceWatermark(7L, CUTOFF);
        assertThat(run).isEqualTo(new DigestRun(1, 1, 0, 0));
    }

    @Test
    void advancesToTheCutoffAndNotToNow() {
        given(List.of(subscriber(7, "bg")));
        withSomethingNew();

        service.sendDailyDigest();

        // The cutoff, not `now`. Advancing to `now` would step over the lag
        // window and skip any action that committed during the run -- an action
        // nobody is ever told about, which is the failure the window exists to
        // prevent.
        ArgumentCaptor<Instant> advanced = ArgumentCaptor.forClass(Instant.class);
        verify(subscribers).advanceWatermark(eq(7L), advanced.capture());
        assertThat(advanced.getValue()).isEqualTo(CUTOFF).isNotEqualTo(NOW);
    }

    @Test
    void leavesTheWatermarkAloneWhenTheSendFails() {
        given(List.of(subscriber(7, "bg")));
        withSomethingNew();
        doThrow(new EmailDeliveryException("Brevo is having an afternoon", new RuntimeException()))
                .when(sender).send(any());

        DigestRun run = service.sendDailyDigest();

        // THE assertion of this file. A watermark that moved here would mean a
        // provider outage silently ate a day of actions for this subscriber,
        // with nothing recorded and nothing to replay. Left alone, tomorrow's
        // run carries today's actions as well.
        verify(subscribers, never()).advanceWatermark(anyLong(), any());
        assertThat(run).isEqualTo(new DigestRun(1, 0, 0, 1));
    }

    @Test
    void leavesTheWatermarkAloneWhenThereIsNothingNew() {
        given(List.of(subscriber(7, "bg")));
        withNothingNew();

        DigestRun run = service.sendDailyDigest();

        // No email, and no watermark advance either: ADR-005's loop only moves
        // it on a send. Leaving it is what lets somebody who later follows
        // another category still receive what was published in the meantime,
        // rather than having been silently stepped over.
        verify(sender, never()).send(any());
        verify(subscribers, never()).advanceWatermark(anyLong(), any());
        assertThat(run).isEqualTo(new DigestRun(1, 0, 1, 0));
    }

    @Test
    void sendsNothingAtAllWhenNobodyIsSubscribed() {
        given(List.of());

        assertThat(service.sendDailyDigest()).isEqualTo(new DigestRun(0, 0, 0, 0));
        verify(sender, never()).send(any());
    }

    // ------------------------------------------------------------------
    // One failure does not end the run
    // ------------------------------------------------------------------

    @Test
    void keepsGoingAfterOneSubscriberFails() {
        given(List.of(subscriber(1, "bg"), subscriber(2, "bg"), subscriber(3, "bg")));
        withSomethingNew();
        // The middle one fails. Letting it propagate would silently cancel
        // every subscriber after it in id order, which is both the wrong
        // outcome and an arbitrary one.
        doThrow(new EmailDeliveryException("one bad address", new RuntimeException()))
                .when(sender).send(argThatGoesTo("reader2@test.invalid"));

        DigestRun run = service.sendDailyDigest();

        assertThat(run).isEqualTo(new DigestRun(3, 2, 0, 1));
        verify(subscribers).advanceWatermark(1L, CUTOFF);
        verify(subscribers).advanceWatermark(3L, CUTOFF);
        verify(subscribers, never()).advanceWatermark(eq(2L), any());
    }

    @Test
    void reportsADeliveredDigestAsSentEvenIfTheWatermarkAdvanceThrows() {
        given(List.of(subscriber(7, "bg")));
        withSomethingNew();
        when(subscribers.advanceWatermark(anyLong(), any()))
                .thenThrow(new IllegalStateException("the database went away mid-run"));

        DigestRun run = service.sendDailyDigest();

        // The email left. Counting this FAILED would have DigestRun describing
        // the opposite of what happened -- and `failed` is the one number an
        // operator watches. The duplicate that is now queued for tomorrow is a
        // log line, not a count: see DigestService.advanceWatermark.
        verify(sender).send(any());
        assertThat(run).isEqualTo(new DigestRun(1, 1, 0, 0));
    }

    @Test
    void doesNotLetOneSubscribersWatermarkFailureCostAnotherTheirDigest() {
        given(List.of(subscriber(1, "bg"), subscriber(2, "bg")));
        withSomethingNew();
        when(subscribers.advanceWatermark(eq(1L), any()))
                .thenThrow(new IllegalStateException("one bad row"));
        when(subscribers.advanceWatermark(eq(2L), any())).thenReturn(1);

        DigestRun run = service.sendDailyDigest();

        // Both emails go out and the second watermark still moves, because the
        // advance is caught per subscriber rather than ending the run.
        assertThat(run).isEqualTo(new DigestRun(2, 2, 0, 0));
        verify(subscribers).advanceWatermark(2L, CUTOFF);
    }

    @Test
    void survivesAFailureInTheQueryAsWellAsInTheSend() {
        given(List.of(subscriber(1, "bg"), subscriber(2, "bg")));
        when(digests.digestFor(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("the database went away"))
                .thenReturn(List.of(anAction()));

        DigestRun run = service.sendDailyDigest();

        // The catch is around the whole per-subscriber block, not only the
        // send, so a failure reading one subscriber's digest costs that
        // subscriber and nobody else.
        assertThat(run).isEqualTo(new DigestRun(2, 1, 0, 1));
    }

    // ------------------------------------------------------------------
    // One clock read for the whole run
    // ------------------------------------------------------------------

    @Test
    void usesOneCutoffForEverySubscriberInTheRun() {
        given(List.of(subscriber(1, "bg"), subscriber(2, "bg")));
        withSomethingNew();

        service.sendDailyDigest();

        // Reading the clock per subscriber would give the last one a different
        // cutoff from the first, so two subscribers could straddle a
        // publication and the watermarks would stop meaning one consistent
        // thing across the run.
        verify(subscribers, times(2)).advanceWatermark(anyLong(), eq(CUTOFF));

        ArgumentCaptor<Instant> cutoffs = ArgumentCaptor.forClass(Instant.class);
        verify(digests, times(2)).digestFor(any(), cutoffs.capture(), any(), any());
        assertThat(cutoffs.getAllValues()).containsOnly(CUTOFF);
    }

    // ------------------------------------------------------------------
    // What the email is given
    // ------------------------------------------------------------------

    @Test
    void composesInTheRecipientsStoredLanguage() {
        given(List.of(subscriber(1, "en")));
        withSomethingNew();

        service.sendDailyDigest();

        // Never LocaleContextHolder and never the server default: a nightly job
        // has no request and no cookie, which is the entire reason
        // app_user.locale exists (ADR-005).
        verify(composer).compose(anyString(), eq(Locale.forLanguageTag("en")), any(), any(), any());
    }

    @Test
    void putsAWorkingUnsubscribeLinkInTheModel() {
        given(List.of(subscriber(42, "bg")));
        withSomethingNew();

        service.sendDailyDigest();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> model = ArgumentCaptor.forClass(Map.class);
        verify(composer).compose(anyString(), any(), any(), model.capture(), any());

        // The legal requirement, and the one email of the four that carries it
        // (ADR-005). Absolute, because an email has no origin to be relative
        // to, and built from the configured base URL rather than from a request
        // the job does not have.
        assertThat(model.getValue().get("unsubscribeUrl"))
                .isEqualTo("https://test.invalid/unsubscribe?token=1.9999999999.mac");
        verify(subscriptions).unsubscribeToken(42L);
    }

    @Test
    void putsTheActionCountInTheSubjectArguments() {
        given(List.of(subscriber(1, "bg")));
        when(digests.digestFor(any(), any(), any(), any()))
                .thenReturn(List.of(anAction(), anAction(), anAction()));

        service.sendDailyDigest();

        // A varying subject, so a month of daily digests does not collapse into
        // one threaded conversation in Gmail with yesterday's still unread.
        // The count is passed as a Number rather than a String because the
        // bundle resolves it through a MessageFormat choice -- "1 ново
        // действие" against "3 нови действия" -- and choice needs a number.
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(composer).compose(anyString(), any(), any(), any(), arguments.capture());
        assertThat(arguments.getValue()).containsExactly(3);
        assertThat(arguments.getValue()[0]).isInstanceOf(Number.class);
    }

    @Test
    void passesOneAsANumberForASingleActionDigest() {
        given(List.of(subscriber(1, "bg")));
        withSomethingNew();

        service.sendDailyDigest();

        // One action is the commonest digest this site will ever send, and it is
        // the case the subject's plural has to get right. The grammar itself is
        // asserted against the real bundles in DigestEndToEndTest; what this
        // pins is that the service hands the count over at all.
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(composer).compose(anyString(), any(), any(), any(), arguments.capture());
        assertThat(arguments.getValue()).containsExactly(1);
    }

    /**
     * Matches the message addressed to one recipient, so a test can fail the
     * send for exactly one subscriber in a run.
     */
    private static EmailMessage argThatGoesTo(String recipient) {
        return org.mockito.ArgumentMatchers.argThat(
                message -> message != null && message.to().equals(recipient));
    }
}
