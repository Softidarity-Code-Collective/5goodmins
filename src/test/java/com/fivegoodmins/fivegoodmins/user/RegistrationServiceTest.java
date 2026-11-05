package com.fivegoodmins.fivegoodmins.user;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.action.Action;
import com.fivegoodmins.fivegoodmins.action.ActionQueries;
import com.fivegoodmins.fivegoodmins.action.ActionRepository;
import com.fivegoodmins.fivegoodmins.action.ActionSummary;
import com.fivegoodmins.fivegoodmins.action.ActionType;
import com.fivegoodmins.fivegoodmins.shared.token.SignedTokenProperties;
import com.fivegoodmins.fivegoodmins.shared.token.SignedTokenService;
import com.fivegoodmins.fivegoodmins.shared.token.TokenPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

/**
 * Against a real Postgres, not Mockito, and that is the decision this class
 * is making rather than a default it fell into.
 *
 * <p>What is worth proving about registration is almost entirely database
 * behaviour. That replacing an unverified row works at all depends on
 * Hibernate ordering a DELETE before an INSERT, which it does not do without
 * the explicit flush -- against a mock repository the flush is an unverified
 * call and the constraint that would have caught its absence does not exist.
 * uq_app_user_email, ck_app_user_email_lowercase and the cutoff in the purge
 * query are in the same position. A Mockito version of this file would be
 * mocking the only thing under test.
 *
 * <p>The clock is mutable because three of the rules are about time passing
 * -- the token's seven days, the purge's seven days, and the fact that they
 * are the same seven days -- and the alternative is a test that sleeps.
 */
@Import({TestcontainersConfiguration.class,
        RegistrationServiceTest.Collaborators.class,
        SignedTokenService.class,
        // @DataJpaTest scans repositories, not @Service beans, so the read side
        // of action/ has to be imported by name alongside the service that
        // takes it.
        ActionQueries.class,
        RegistrationService.class})
@DataJpaTest
class RegistrationServiceTest {

    private static final String ADDRESS = "georgi@seed.example";
    private static final String PASSWORD = "a-perfectly-ordinary-password";

    @Autowired
    RegistrationService registrations;

    @Autowired
    AppUserRepository users;

    @Autowired
    ActionRepository actions;

    @Autowired
    SignedTokenService tokens;

    @Autowired
    CountingPasswordEncoder passwordEncoder;

    @Autowired
    MutableClock clock;

    @Autowired
    RecordedEmails emails;

    @BeforeEach
    void resetTheClock() {
        // The context is cached across methods, so the clock has to go back to
        // a baseline or the first test that advances it poisons the rest. Real
        // "now" rather than a literal, because created_at is written by
        // @CreationTimestamp from the system clock and the purge compares the
        // two: a fixed date here would make the cutoff arithmetic depend on
        // what day the suite runs.
        clock.set(Instant.now());
        passwordEncoder.resetCount();
        emails.clear();
    }

    private RegistrationOutcome.Registered register(String email, String password, String name) {
        RegistrationOutcome outcome = registrations.register(email, password, name);
        assertThat(outcome).isInstanceOf(RegistrationOutcome.Registered.class);
        return (RegistrationOutcome.Registered) outcome;
    }

    /**
     * The verification token, read from the published event rather than from
     * the outcome -- because the outcome no longer carries one, and because
     * every test that confirms an address now also asserts that registering
     * published exactly one event for that account.
     */
    private String tokenFor(RegistrationOutcome.Registered registered) {
        return emails.verificationFor(registered.userId()).token();
    }

    // -----------------------------------------------------------------------
    // Registering
    // -----------------------------------------------------------------------

    @Test
    void storesTheAddressLowercasedAndTheNameTrimmed() {
        register("Georgi@Seed.Example", PASSWORD, "  Georgi  ");

        assertThat(users.findByEmail(ADDRESS))
                .get()
                .satisfies(user -> {
                    assertThat(user.getEmail()).isEqualTo(ADDRESS);
                    assertThat(user.getDisplayName()).isEqualTo("Georgi");
                });
        // The row only exists because the application normalized: the database
        // would have refused "Georgi@Seed.Example" on
        // ck_app_user_email_lowercase.
    }

    @Test
    void storesAnEncodedPasswordCarryingTheAlgorithmThatProducedIt() {
        register(ADDRESS, PASSWORD, "Georgi");

        String stored = users.findByEmail(ADDRESS).orElseThrow().getPasswordHash();

        assertThat(stored).startsWith("{bcrypt}").doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, stored)).isTrue();
    }

    @Test
    void createsAnAccountThatCannotLogInUntilItIsVerified() {
        register(ADDRESS, PASSWORD, "Georgi");

        AppUser user = users.findByEmail(ADDRESS).orElseThrow();

        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getEmailVerifiedAt()).isNull();
        // enabled is the ban switch and starts true; verification is the other
        // half of the gate, and it is this one that is still closed.
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void startsTheDigestWatermarkAtTheInjectedClockRatherThanTheSystemOne() {
        Instant frozen = Instant.parse("2026-10-15T09:00:00Z");
        clock.set(frozen);

        register(ADDRESS, PASSWORD, "Georgi");

        // Not a style point: this is what keeps the first digest from
        // containing the whole back catalogue (ADR-005), and it is only
        // assertable because the service takes a Clock.
        assertThat(users.findByEmail(ADDRESS).orElseThrow().getLastDigestSentAt())
                .isEqualTo(frozen);
    }

    // -----------------------------------------------------------------------
    // Verifying
    // -----------------------------------------------------------------------

    @Test
    void issuesATokenThatVerifiesTheAccountItNames() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Georgi");

        assertThat(registrations.verifyEmail(tokenFor(registered)))
                .isEqualTo(new EmailVerificationOutcome.Verified(registered.userId()));
        assertThat(users.findByEmail(ADDRESS).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void reportsASecondClickSeparatelySoTheWelcomeEmailIsSentOnce() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Georgi");
        String link = tokenFor(registered);
        Instant firstVerification = Instant.parse("2026-10-15T09:00:00Z");

        clock.set(firstVerification);
        registrations.verifyEmail(link);
        clock.advance(Duration.ofHours(1));

        // A mail client prefetching the link and the user then clicking it is
        // the ordinary case, not an edge one.
        assertThat(registrations.verifyEmail(link))
                .isEqualTo(new EmailVerificationOutcome.AlreadyVerified(registered.userId()));
        assertThat(users.findByEmail(ADDRESS).orElseThrow().getEmailVerifiedAt())
                .isEqualTo(firstVerification);
        // markEmailVerified writes once: the hour that passed is not in the row.
    }

    @Test
    void sendsAUserWhoseLinkAgedOutBackToRegistrationRatherThanToAResend() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Georgi");

        clock.advance(TokenPurpose.EMAIL_VERIFICATION.ttl().plusSeconds(1));

        // DeadLink rather than a separate Expired, because the row this names
        // is already past the purge cutoff -- the two windows are the same
        // seven days -- so a resent link would die at the next nightly run.
        // Registering again replaces the row and issues a fresh token, which
        // is what a resend would have had to do anyway.
        assertThat(registrations.verifyEmail(tokenFor(registered)))
                .isEqualTo(new EmailVerificationOutcome.DeadLink());
        assertThat(users.findByEmail(ADDRESS).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void tellsSomeoneReclickingAnOldConfirmedLinkThatTheyAreAlreadyDone() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Georgi");
        registrations.verifyEmail(tokenFor(registered));

        clock.advance(TokenPurpose.EMAIL_VERIFICATION.ttl().plusDays(2));

        // The case that makes TokenVerification.Expired carry a user id.
        // Answering from the expiry alone would say "register again", and
        // registering again returns AddressTaken and mails nothing -- a loop
        // ending at "check your inbox" for an email that never arrives.
        assertThat(registrations.verifyEmail(tokenFor(registered)))
                .isEqualTo(new EmailVerificationOutcome.AlreadyVerified(registered.userId()));
    }

    @Test
    void refusesALinkThatWasNotIssuedForVerification() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Georgi");

        // The unsubscribe link in every digest email names the same user and is
        // signed with the same key. Only the purpose in the signed material
        // stops it confirming an address nobody read.
        String unsubscribe = tokens.issue(registered.userId(), TokenPurpose.UNSUBSCRIBE);

        assertThat(registrations.verifyEmail(unsubscribe))
                .isEqualTo(new EmailVerificationOutcome.Invalid());
        assertThat(users.findByEmail(ADDRESS).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void refusesSomethingThatIsNotATokenAtAll() {
        assertThat(registrations.verifyEmail("1.9999999999.forged"))
                .isEqualTo(new EmailVerificationOutcome.Invalid());
        assertThat(registrations.verifyEmail(null))
                .isEqualTo(new EmailVerificationOutcome.Invalid());
    }

    @Test
    void reportsAnAuthenticLinkWhoseAccountIsGoneAsDead() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Georgi");
        users.deleteById(registered.userId());
        users.flush();

        // Still in date, so this is the lookup rather than the expiry
        // answering: an authentic token is not a promise that the row exists.
        assertThat(registrations.verifyEmail(tokenFor(registered)))
                .isEqualTo(new EmailVerificationOutcome.DeadLink());
    }

    // -----------------------------------------------------------------------
    // Re-registration over an unverified row
    // -----------------------------------------------------------------------

    @Test
    void letsASecondPersonTakeOverAnAddressNobodyHasProvedTheyCanRead() {
        register(ADDRESS, "the-squatters-password", "Squatter");

        RegistrationOutcome.Registered owner = register(ADDRESS, PASSWORD, "Georgi");

        assertThat(users.count()).isOne();
        AppUser row = users.findByEmail(ADDRESS).orElseThrow();
        assertThat(row.getId()).isEqualTo(owner.userId());
        assertThat(row.getDisplayName()).isEqualTo("Georgi");
        assertThat(passwordEncoder.matches(PASSWORD, row.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("the-squatters-password", row.getPasswordHash())).isFalse();
        // That this reaches the database at all is the DELETE-before-INSERT
        // flush doing its job; without it uq_app_user_email rejects the insert.
    }

    @Test
    void killsTheSupersededLinkWhenAnAddressIsTakenOver() {
        RegistrationOutcome.Registered squatter = register(ADDRESS, "the-squatters-password", "Squatter");

        RegistrationOutcome.Registered owner = register(ADDRESS, PASSWORD, "Georgi");

        // The whole reason the row is replaced rather than updated. Had it
        // been updated in place, the id would be unchanged and the first
        // token would still verify -- so the real owner, clicking the link
        // from their own first registration, would confirm an account whose
        // password belongs to someone else.
        assertThat(squatter.userId()).isNotEqualTo(owner.userId());
        assertThat(registrations.verifyEmail(tokenFor(squatter)))
                .isEqualTo(new EmailVerificationOutcome.DeadLink());
        assertThat(registrations.verifyEmail(tokenFor(owner)))
                .isEqualTo(new EmailVerificationOutcome.Verified(owner.userId()));
    }

    @Test
    void refusesToTouchAVerifiedAccountAndDeclinesToSaySo() {
        RegistrationOutcome.Registered owner = register(ADDRESS, PASSWORD, "Georgi");
        registrations.verifyEmail(tokenFor(owner));

        RegistrationOutcome outcome = registrations.register(ADDRESS, "an-attackers-password", "Not Georgi");

        // AddressTaken carries no id and no token, so there is nothing for a
        // caller to leak even by accident; the page it renders is the one
        // Registered renders.
        assertThat(outcome).isEqualTo(new RegistrationOutcome.AddressTaken());
        assertThat(users.count()).isOne();
        AppUser row = users.findByEmail(ADDRESS).orElseThrow();
        assertThat(row.getId()).isEqualTo(owner.userId());
        assertThat(row.getDisplayName()).isEqualTo("Georgi");
        assertThat(passwordEncoder.matches(PASSWORD, row.getPasswordHash())).isTrue();
    }

    // -----------------------------------------------------------------------
    // What the two registration outcomes have to have in common
    // -----------------------------------------------------------------------

    @Test
    void hashesAPasswordEvenForAnAddressItIsAboutToRefuse() {
        RegistrationOutcome.Registered owner = register(ADDRESS, PASSWORD, "Georgi");
        registrations.verifyEmail(tokenFor(owner));
        passwordEncoder.resetCount();

        assertThat(registrations.register(ADDRESS, "an-attackers-password", "Not Georgi"))
                .isEqualTo(new RegistrationOutcome.AddressTaken());

        // Asserted structurally rather than with a stopwatch: a timing test
        // is a flaky test, and what is actually being promised is that the
        // expensive call happens on both paths. BCrypt is ~100ms against a
        // millisecond of everything else, so skipping it here would make
        // response time the answer to "does this address have an account?" --
        // and undo, with a stopwatch, the identical page the two outcomes
        // deliberately render.
        assertThat(passwordEncoder.count()).isOne();
    }

    @Test
    void hashesAPasswordExactlyOnceOnTheRegisteringPathToo() {
        passwordEncoder.resetCount();

        register(ADDRESS, PASSWORD, "Georgi");

        // The other half of the pair: equal cost means equal, not merely
        // non-zero on both sides.
        assertThat(passwordEncoder.count()).isOne();
    }

    @Test
    void publishesOneEmailEventOnTheRegisteringPath() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "  Georgi  ");

        assertThat(emails.all()).singleElement()
                .isInstanceOfSatisfying(RegistrationEmailEvent.Verification.class, event -> {
                    assertThat(event.userId()).isEqualTo(registered.userId());
                    // The normalized address and the trimmed name, read back
                    // from the row rather than from the arguments: the email
                    // goes where the row says, not where the form said.
                    assertThat(event.email()).isEqualTo(ADDRESS);
                    assertThat(event.displayName()).isEqualTo("Georgi");
                    assertThat(event.locale()).isEqualTo(Locale.forLanguageTag("bg"));
                    assertThat(event.token()).isNotBlank();
                });
    }

    @Test
    void publishesOneEmailEventOnTheRefusingPathToo() {
        RegistrationOutcome.Registered owner = register(ADDRESS, PASSWORD, "Georgi");
        registrations.verifyEmail(tokenFor(owner));
        emails.clear();

        assertThat(registrations.register(ADDRESS, "an-attackers-password", "Not Georgi"))
                .isEqualTo(new RegistrationOutcome.AddressTaken());

        // The requirement ADR-005 states before the sender exists, and the one
        // that is easiest to leave out: if only the registering path published,
        // only the registering path would pay the mail provider's 100-500ms,
        // and the identical page the two outcomes render would be undone by a
        // stopwatch -- several times more loudly than the BCrypt gap that was
        // closed by hashing on both paths.
        assertThat(emails.all()).singleElement()
                .isInstanceOfSatisfying(RegistrationEmailEvent.ExistingAccount.class, event -> {
                    assertThat(event.userId()).isEqualTo(owner.userId());
                    assertThat(event.email()).isEqualTo(ADDRESS);
                    // The owner's name, not the name the stranger typed: this
                    // email is addressed to whoever reads the mailbox.
                    assertThat(event.displayName()).isEqualTo("Georgi");
                });
    }

    @Test
    void publishesNothingWhenItRefusesTheInputOutright() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> registrations.register(ADDRESS, "short", "Georgi"));

        // A rejected form is not a registration attempt worth emailing anyone
        // about -- and the address may well belong to someone who did not ask
        // for mail from this site.
        assertThat(emails.all()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // The purge
    // -----------------------------------------------------------------------

    @Test
    void leavesUnverifiedAccountsAloneForAsLongAsTheirLinkIsStillGood() {
        register(ADDRESS, PASSWORD, "Georgi");

        clock.advance(TokenPurpose.EMAIL_VERIFICATION.ttl().minus(Duration.ofHours(1)));

        assertThat(registrations.purgeUnverifiedAccounts()).isZero();
        assertThat(users.findByEmail(ADDRESS)).isPresent();
        // One hour before the purge window is one hour before the token
        // expires, because they are the same window read from the same place.
    }

    @Test
    void removesUnverifiedAccountsOnceTheirLinkHasDiedAndKeepsVerifiedOnes() {
        register(ADDRESS, PASSWORD, "Georgi");
        RegistrationOutcome.Registered verified = register("verified@seed.example", PASSWORD, "Verified");
        registrations.verifyEmail(tokenFor(verified));

        clock.advance(TokenPurpose.EMAIL_VERIFICATION.ttl().plus(Duration.ofHours(1)));

        assertThat(registrations.purgeUnverifiedAccounts()).isOne();
        assertThat(users.findByEmail(ADDRESS)).isEmpty();
        assertThat(users.findByEmail("verified@seed.example")).isPresent();
        // The second assertion is the one that would fail if the bulk delete
        // ran before the verification reached the database -- which is what
        // flushAutomatically is for.
    }

    @Test
    void freesThePurgedAddressForWhoeverWantsItNext() {
        register(ADDRESS, "the-squatters-password", "Squatter");
        clock.advance(TokenPurpose.EMAIL_VERIFICATION.ttl().plus(Duration.ofHours(1)));
        registrations.purgeUnverifiedAccounts();

        register(ADDRESS, PASSWORD, "Georgi");

        assertThat(users.count()).isOne();
        assertThat(passwordEncoder.matches(PASSWORD,
                users.findByEmail(ADDRESS).orElseThrow().getPasswordHash())).isTrue();
    }

    // -----------------------------------------------------------------------
    // The backstop checks
    // -----------------------------------------------------------------------

    @Test
    void refusesAPasswordThatBCryptWouldSilentlyCutShortInBulgarian() {
        // Thirty-seven Cyrillic characters: well under any character limit a
        // form would advertise, and 74 bytes in UTF-8 -- past BCrypt's 72.
        // An ASCII password of the same length is nowhere near it, which is
        // exactly why counting characters here would be a bug aimed at this
        // site's own users.
        String passphrase = "паролатамитрябвадаебезмногоотдалеченост";
        assertThat(passphrase.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isGreaterThan(72);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> registrations.register(ADDRESS, passphrase, "Georgi"))
                .withMessageContaining("72 bytes");
        assertThat(users.count()).isZero();
    }

    @Test
    void refusesInputTheSchemaWouldRejectLaterAndLessClearly() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> registrations.register("  ", PASSWORD, "Georgi"))
                .withMessageContaining("email");

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> registrations.register(ADDRESS, PASSWORD, "   "))
                .withMessageContaining("display name");
        // Three spaces, because ck_app_user_display_name_length compares
        // btrim(display_name) and this is the value that gets past a naive
        // "not empty" check.

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> registrations.register(ADDRESS, "short", "Georgi"))
                .withMessageContaining("8 characters");

        assertThat(users.count()).isZero();
    }

    // -----------------------------------------------------------------------

    @TestConfiguration(proxyBeanMethods = false)
    static class Collaborators {

        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.now());
        }

        /**
         * The real signing key requirements, not a stub: the service under
         * test hands this class a user id and gets back something only this
         * key can produce, and shortening it here would quietly weaken what
         * the token tests prove elsewhere.
         */
        @Bean
        SignedTokenProperties signedTokenProperties() {
            return new SignedTokenProperties("a-test-signing-key-of-at-least-32-chars");
        }

        /**
         * The production encoder, deliberately, with a counter around it. A
         * no-op one would run faster and would also stop these tests from
         * noticing the day {@code password_hash} is too short for the
         * algorithm in use, which is a failure the migration's comment is
         * specifically about.
         */
        @Bean
        CountingPasswordEncoder passwordEncoder() {
            return new CountingPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        }

        @Bean
        RecordedEmails recordedEmails() {
            return new RecordedEmails();
        }
    }

    /**
     * Captures what {@code register} publishes.
     *
     * <p>A plain {@code @EventListener}, not {@code @TransactionalEventListener}:
     * this test's transaction is rolled back by {@code @DataJpaTest} and never
     * commits, so an after-commit listener would record nothing and every
     * assertion here would pass for the wrong reason. What belongs in this file
     * is <em>that</em> the events are published, with the right values, inside
     * the transaction that wrote the row. That they are then delivered after
     * commit and off the request thread is {@code RegistrationEmailListenerTest},
     * where a real transaction is committed on purpose.
     */
    static final class RecordedEmails {

        private final List<RegistrationEmailEvent> recorded = new ArrayList<>();

        @EventListener
        void record(RegistrationEmailEvent event) {
            recorded.add(event);
        }

        List<RegistrationEmailEvent> all() {
            return List.copyOf(recorded);
        }

        void clear() {
            recorded.clear();
        }

        /** Every welcome email published for this account -- expected to be at most one. */
        List<RegistrationEmailEvent.Welcome> welcomesFor(long userId) {
            return recorded.stream()
                    .filter(RegistrationEmailEvent.Welcome.class::isInstance)
                    .map(RegistrationEmailEvent.Welcome.class::cast)
                    .filter(event -> event.userId() == userId)
                    .toList();
        }

        /** The one verification email published for this account. */
        RegistrationEmailEvent.Verification verificationFor(long userId) {
            List<RegistrationEmailEvent.Verification> matching = recorded.stream()
                    .filter(RegistrationEmailEvent.Verification.class::isInstance)
                    .map(RegistrationEmailEvent.Verification.class::cast)
                    .filter(event -> event.userId() == userId)
                    .toList();
            assertThat(matching).as("verification emails published for account %s", userId).hasSize(1);
            return matching.getFirst();
        }
    }

    /**
     * Counts {@code encode} calls, so that "both paths pay the same cost" can
     * be asserted as a fact about the code rather than measured as a duration.
     */
    static final class CountingPasswordEncoder implements PasswordEncoder {

        private final PasswordEncoder delegate;
        private final AtomicInteger encodes = new AtomicInteger();

        CountingPasswordEncoder(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        int count() {
            return encodes.get();
        }

        void resetCount() {
            encodes.set(0);
        }

        @Override
        public String encode(CharSequence rawPassword) {
            encodes.incrementAndGet();
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return delegate.matches(rawPassword, encodedPassword);
        }
    }

    /**
     * A clock the test moves by hand. {@code Clock.fixed} cannot express "and
     * then seven days pass", and {@code Thread.sleep} can only express it by
     * taking seven days.
     */
    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            this.instant = this.instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("the application uses one zone: UTC");
        }
    }

    // -----------------------------------------------------------------------
    // The welcome email (ADR-005). What belongs here is *when* it is published
    // and *what* it carries; that it is then delivered after commit and off
    // the request thread is RegistrationEmailListenerTest's subject.
    // -----------------------------------------------------------------------

    private Action publishedAction(AppUser author, String title) {
        Action action = new Action(author, title, "Opisanie na akciata",
                ActionType.PETITION, clock.instant().plus(Duration.ofDays(30)));
        action.publish(clock.instant());
        return actions.saveAndFlush(action);
    }

    @Test
    void publishesAWelcomeEmailOnTheTransitionThatConfirmsTheAddress() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Георги");
        String link = tokenFor(registered);
        emails.clear();

        assertThat(registrations.verifyEmail(link))
                .isInstanceOf(EmailVerificationOutcome.Verified.class);

        assertThat(emails.welcomesFor(registered.userId()))
                .singleElement()
                .satisfies(welcome -> {
                    assertThat(welcome.email()).isEqualTo(ADDRESS);
                    assertThat(welcome.displayName()).isEqualTo("Георги");
                    assertThat(welcome.locale()).isEqualTo(Locale.forLanguageTag("bg"));
                });
    }

    /**
     * The reason no column records that the welcome was sent (ADR-005). A mail
     * client prefetching the link and a user clicking it twice are both
     * ordinary, and both reach the same page -- but only the write publishes,
     * so {@code email_verified_at IS NOT NULL} is the record.
     */
    @Test
    void publishesNoSecondWelcomeWhenTheSameLinkIsUsedAgain() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Георги");
        String link = tokenFor(registered);
        emails.clear();

        registrations.verifyEmail(link);
        registrations.verifyEmail(link);
        registrations.verifyEmail(link);

        assertThat(emails.welcomesFor(registered.userId())).hasSize(1);
    }

    @Test
    void publishesNoWelcomeForALinkThatHasExpired() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Георги");
        String link = tokenFor(registered);
        emails.clear();

        clock.advance(TokenPurpose.EMAIL_VERIFICATION.ttl().plusDays(1));

        assertThat(registrations.verifyEmail(link))
                .isInstanceOf(EmailVerificationOutcome.DeadLink.class);
        assertThat(emails.welcomesFor(registered.userId())).isEmpty();
    }

    @Test
    void carriesTheFiveNewestActiveActionsAndNoMore() {
        AppUser poster = users.saveAndFlush(
                new AppUser("poster@seed.example", "{bcrypt}x", "Постващ", clock.instant()));
        for (int i = 1; i <= 7; i++) {
            publishedAction(poster, "Akcia " + i);
        }

        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Георги");
        String link = tokenFor(registered);
        emails.clear();
        registrations.verifyEmail(link);

        assertThat(emails.welcomesFor(registered.userId()))
                .singleElement()
                .extracting(RegistrationEmailEvent.Welcome::actions, list(ActionSummary.class))
                .hasSize(5)
                .allSatisfy(action -> assertThat(action.title()).startsWith("Akcia "));
    }

    /**
     * The empty site: a fresh deployment before the seed migration, or before
     * anybody has submitted anything. An empty list rather than a failure,
     * because the template has a line for it.
     */
    @Test
    void carriesAnEmptyListWhenNothingIsPublishedYet() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Георги");
        String link = tokenFor(registered);
        emails.clear();

        registrations.verifyEmail(link);

        assertThat(emails.welcomesFor(registered.userId()))
                .singleElement()
                .extracting(RegistrationEmailEvent.Welcome::actions, list(ActionSummary.class))
                .isEmpty();
    }

    /**
     * ADR-005, in one line: the welcome email does not touch
     * {@code last_digest_sent_at}. That column is the digest's watermark and
     * was set to the registration instant by the column default; advancing it
     * here would make the welcome email suppress the first digest, which is
     * the opposite of what either is for. Worth asserting rather than trusting,
     * because the column is one setter away and the symptom would be a digest
     * that silently never arrives.
     */
    @Test
    void leavesTheDigestWatermarkWhereRegistrationPutIt() {
        RegistrationOutcome.Registered registered = register(ADDRESS, PASSWORD, "Георги");
        Instant watermarkAtRegistration =
                users.findById(registered.userId()).orElseThrow().getLastDigestSentAt();

        clock.advance(Duration.ofHours(3));
        registrations.verifyEmail(tokenFor(registered));
        users.flush();

        AppUser confirmed = users.findById(registered.userId()).orElseThrow();
        assertThat(confirmed.isEmailVerified()).isTrue();
        assertThat(confirmed.getLastDigestSentAt()).isEqualTo(watermarkAtRegistration);
    }
}
