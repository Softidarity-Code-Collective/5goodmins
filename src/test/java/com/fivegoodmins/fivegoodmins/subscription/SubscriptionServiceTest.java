package com.fivegoodmins.fivegoodmins.subscription;

import com.fivegoodmins.fivegoodmins.TestcontainersConfiguration;
import com.fivegoodmins.fivegoodmins.action.Category;
import com.fivegoodmins.fivegoodmins.action.Region;
import com.fivegoodmins.fivegoodmins.shared.token.SignedTokenProperties;
import com.fivegoodmins.fivegoodmins.shared.token.SignedTokenService;
import com.fivegoodmins.fivegoodmins.shared.token.TokenPurpose;
import com.fivegoodmins.fivegoodmins.user.AppUser;
import com.fivegoodmins.fivegoodmins.user.AppUserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a user follows, round-tripped through the two collection tables, and the
 * unsubscribe path that writes without a login.
 *
 * <p><strong>Against a real PostgreSQL rather than mocks</strong>, because
 * three of the four things worth asserting here are properties of the schema or
 * of Hibernate's element-collection handling rather than of the Java: that the
 * default is subscribed, that replacing a selection does not violate
 * {@code pk_subscription_category}, and that an absent selection comes back
 * empty rather than null.
 */
@Import({TestcontainersConfiguration.class, SubscriptionService.class, SignedTokenService.class,
        SubscriptionServiceTest.TokenConfiguration.class})
@DataJpaTest
class SubscriptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T07:00:00Z");

    /**
     * The real {@link SignedTokenService}, not a mock, because the unsubscribe
     * tests need tokens that genuinely verify -- and because the one thing most
     * worth asserting is that a verification token does <em>not</em> work as an
     * opt-out, which only the real signing can show.
     *
     * <p>Its two dependencies by hand: {@code @DataJpaTest} is a slice, so
     * neither {@code @ConfigurationProperties} binding nor {@code TimeConfig}'s
     * {@code Clock} is in the context. A fixed clock rather than
     * {@code systemUTC}, so nothing here depends on the real date -- the
     * {@code UNSUBSCRIBE} lifetime is ten years and would pass either way,
     * which is exactly the kind of accident worth not relying on.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TokenConfiguration {

        @Bean
        SignedTokenProperties signedTokenProperties() {
            return new SignedTokenProperties("test-only-signing-key-at-least-32-characters");
        }

        /**
         * Movable rather than fixed, which is what lets the expired-token test
         * below exist at all: an {@code UNSUBSCRIBE} token is signed for ten
         * years, so the only way to see {@link SignedTokenService} report it
         * expired is to move the clock past that. ADR-019 names this technique
         * directly -- "a fixed Clock proves a boundary, a mutable one proves a
         * sequence".
         */
        @Bean
        MovableClock clock() {
            return new MovableClock(NOW);
        }
    }

    /**
     * A {@link Clock} a test can move.
     *
     * <p>{@code SignedTokenServiceTest} has an equivalent of its own and the two
     * are deliberately not shared: it is twelve lines of trivial code, and one
     * shared test fixture reaching across two feature packages is a heavier
     * thing to own than the duplication. If a third test wants one, extract it
     * then.
     */
    static final class MovableClock extends Clock {

        private volatile Instant instant;

        private MovableClock(Instant instant) {
            this.instant = instant;
        }

        void moveTo(Instant later) {
            this.instant = later;
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
            return this;
        }
    }

    @Autowired SubscriptionService subscriptions;
    @Autowired AppUserRepository users;
    @Autowired SignedTokenService tokens;
    @Autowired MovableClock clock;
    @Autowired EntityManager em;

    private AppUser reader;

    @BeforeEach
    void createReader() {
        reader = users.saveAndFlush(
                new AppUser("reader@seed.example", "{bcrypt}x", "Chitatel", NOW));
    }

    // ------------------------------------------------------------------
    // The default, which is the product decision in ADR-023
    // ------------------------------------------------------------------

    @Test
    void subscribesANewAccountByDefault() {
        // digest_enabled DEFAULT true in V2, and defaulted in the field too --
        // Hibernate names every mapped column in its INSERT, so without the
        // field initialiser a new registration would arrive as false and the
        // flagship feature would be off for everybody.
        assertThat(subscriptions.preferencesOf(reader.getId()).digestEnabled()).isTrue();
    }

    @Test
    void startsWithNoSelectionAtAll() {
        SubscriptionPreferences stored = subscriptions.preferencesOf(reader.getId());

        // Empty, not null, and not pre-populated: an account that has never
        // seen the preferences page has no rows in either table.
        assertThat(stored.categories()).isEmpty();
        assertThat(stored.regions()).isEmpty();
    }

    @Test
    void readsAnEmptySelectionAsEveryValue() {
        SubscriptionFilter effective = subscriptions.effectiveFilterFor(reader.getId());

        // The substitution ADR-023 turns on. Read the other way -- empty means
        // nothing matches -- every registrant who never opened the preferences
        // page would be subscribed to a digest that silently matched nothing
        // forever, and the welcome email's promise would be false for most
        // accounts.
        assertThat(effective.categories()).containsExactlyInAnyOrder(Category.values());
        assertThat(effective.regions()).containsExactlyInAnyOrder(Region.values());
    }

    @Test
    void narrowsTheFilterToWhatWasActuallyChosen() {
        subscriptions.save(reader.getId(),
                Set.of(Category.NATURE, Category.ANIMALS),
                Set.of(Region.VARNA),
                true);
        em.flush();
        em.clear();

        SubscriptionFilter effective = subscriptions.effectiveFilterFor(reader.getId());

        assertThat(effective.categories())
                .containsExactlyInAnyOrder(Category.NATURE, Category.ANIMALS);
        assertThat(effective.regions()).containsExactly(Region.VARNA);
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    @Test
    void roundTripsASelectionThroughBothCollectionTables() {
        subscriptions.save(reader.getId(),
                Set.of(Category.NATURE, Category.POLITICAL),
                Set.of(Region.SOFIA_CITY, Region.NATIONAL),
                true);
        em.flush();
        em.clear();   // force a real read, not the persistence context's copy

        SubscriptionPreferences stored = subscriptions.preferencesOf(reader.getId());

        assertThat(stored.categories())
                .containsExactlyInAnyOrder(Category.NATURE, Category.POLITICAL);
        assertThat(stored.regions())
                .containsExactlyInAnyOrder(Region.SOFIA_CITY, Region.NATIONAL);
    }

    @Test
    void replacesTheSelectionRatherThanAddingToIt() {
        subscriptions.save(reader.getId(), Set.of(Category.NATURE), Set.of(Region.VARNA), true);
        em.flush();
        em.clear();

        subscriptions.save(reader.getId(), Set.of(Category.ANIMALS), Set.of(Region.BURGAS), true);
        em.flush();
        em.clear();

        // The form carries the complete selection every time, so what arrives
        // is the new state and not a delta. Merging would make unticking a box
        // impossible.
        SubscriptionPreferences stored = subscriptions.preferencesOf(reader.getId());
        assertThat(stored.categories()).containsExactly(Category.ANIMALS);
        assertThat(stored.regions()).containsExactly(Region.BURGAS);
    }

    @Test
    void savesTheSameSelectionTwiceWithoutViolatingTheCompositeKey() {
        subscriptions.save(reader.getId(),
                Set.of(Category.NATURE, Category.ANIMALS), Set.of(Region.VARNA), true);
        em.flush();
        em.clear();

        // The failure this guards against is the one that comes from assigning
        // a fresh Set over a Hibernate-managed collection: it throws away the
        // tracked instance and the flush re-inserts rows that are already
        // there, violating pk_subscription_category on a save that changed
        // nothing. SubscriptionService mutates in place instead, which is why
        // AppUser exposes no setter for either collection.
        subscriptions.save(reader.getId(),
                Set.of(Category.NATURE, Category.ANIMALS), Set.of(Region.VARNA), true);
        em.flush();
        em.clear();

        assertThat(subscriptions.preferencesOf(reader.getId()).categories())
                .containsExactlyInAnyOrder(Category.NATURE, Category.ANIMALS);
    }

    @Test
    void clearsASelectionEntirely() {
        subscriptions.save(reader.getId(), Set.of(Category.NATURE), Set.of(Region.VARNA), true);
        em.flush();
        em.clear();

        subscriptions.save(reader.getId(), Set.of(), Set.of(), true);
        em.flush();
        em.clear();

        // An empty POST is a valid save, not a validation error -- and it means
        // "everything" from here on, which is the next assertion.
        assertThat(subscriptions.preferencesOf(reader.getId()).categories()).isEmpty();
        assertThat(subscriptions.effectiveFilterFor(reader.getId()).categories())
                .containsExactlyInAnyOrder(Category.values());
    }

    // ------------------------------------------------------------------
    // Unsubscribe, which is the legally required half
    // ------------------------------------------------------------------

    @Test
    void unsubscribesOnAValidToken() {
        String token = subscriptions.unsubscribeToken(reader.getId());

        assertThat(subscriptions.unsubscribe(token)).isTrue();
        em.flush();
        em.clear();

        assertThat(subscriptions.preferencesOf(reader.getId()).digestEnabled()).isFalse();
    }

    @Test
    void unsubscribesIdempotently() {
        String token = subscriptions.unsubscribeToken(reader.getId());

        assertThat(subscriptions.unsubscribe(token)).isTrue();
        // A second click on the same link, or a mail client that posted it
        // twice. There is no token table to record that it was used, which is
        // only acceptable because replay changes nothing -- the property
        // SignedTokenService's own notes rest on.
        assertThat(subscriptions.unsubscribe(token)).isTrue();
        em.flush();
        em.clear();

        assertThat(subscriptions.preferencesOf(reader.getId()).digestEnabled()).isFalse();
    }

    @Test
    void unsubscribesWithAnExpiredToken() {
        String token = subscriptions.unsubscribeToken(reader.getId());

        // Eleven years on, past the ten-year UNSUBSCRIBE lifetime.
        clock.moveTo(NOW.plus(Duration.ofDays(11 * 365)));

        // Deliberately honoured rather than refused, and this is the test that
        // keeps the branch from being "simplified" away. The signature is
        // checked before the expiry is read, so the id on an Expired token is
        // as authentic as the id on a Valid one -- and refusing it would mean
        // telling somebody holding a genuine, this-application-signed opt-out
        // link that they cannot opt out. That is the one answer a legally
        // required mechanism must not give.
        assertThat(subscriptions.unsubscribe(token)).isTrue();
        em.flush();
        em.clear();

        assertThat(subscriptions.preferencesOf(reader.getId()).digestEnabled()).isFalse();
    }

    @Test
    void stillRefusesAnExpiredTokenThatDoesNotVerify() {
        clock.moveTo(NOW.plus(Duration.ofDays(11 * 365)));

        // Expiry is forgiven; a bad signature is not. Otherwise "we honour
        // expired tokens" would quietly mean "we honour anything old".
        assertThat(subscriptions.unsubscribe("1.9999999999.not-a-real-mac")).isFalse();
    }

    @Test
    void refusesATokenThatDoesNotVerify() {
        assertThat(subscriptions.unsubscribe("1.9999999999.not-a-real-mac")).isFalse();
        assertThat(subscriptions.unsubscribe("nonsense")).isFalse();
        assertThat(subscriptions.unsubscribe(null)).isFalse();

        em.flush();
        em.clear();
        assertThat(subscriptions.preferencesOf(reader.getId()).digestEnabled()).isTrue();
    }

    @Test
    void refusesAVerificationTokenOfferedAsAnUnsubscribeLink() {
        // The purpose is part of the signed material, which is the whole reason
        // TokenPurpose exists rather than a lifetime being passed in at the
        // call site: one signing key covers every link this application emails,
        // so without the purpose in the signature this would verify.
        String verification = tokens.issue(reader.getId(), TokenPurpose.EMAIL_VERIFICATION);

        assertThat(subscriptions.unsubscribe(verification)).isFalse();
        em.flush();
        em.clear();
        assertThat(subscriptions.preferencesOf(reader.getId()).digestEnabled()).isTrue();
    }

    @Test
    void reportsFailureForATokenWhoseAccountNoLongerExists() {
        // An unsubscribe token is signed for ten years, so the account behind it
        // can have been deleted (ADR-015) or purged (ADR-002) in the meantime.
        // One return value for this and for a forgery, because the page is the
        // same dead link either way and distinguishing them would tell whoever
        // holds a forged token which half of it was wrong.
        String token = subscriptions.unsubscribeToken(Long.MAX_VALUE);

        assertThat(subscriptions.unsubscribe(token)).isFalse();
    }
}
