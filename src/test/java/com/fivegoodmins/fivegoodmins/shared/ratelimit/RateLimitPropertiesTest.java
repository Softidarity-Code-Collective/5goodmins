package com.fivegoodmins.fivegoodmins.shared.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The numbers from ADR-002, pinned.
 *
 * <p>Asserted for the same reason the HSTS directive set is asserted rather
 * than the presence of {@code max-age}: these are policy, settled with a
 * rationale, and a change to any of them should fail a build and be argued
 * for -- not land quietly because somebody was adjusting a default while
 * debugging something else. The suite runs with
 * {@code app.rate-limit.refuse-requests=false} and the mail cap raised, so this
 * is also the only place the shipped defaults are observed at all.
 */
class RateLimitPropertiesTest {

    private static RateLimitProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("app.rate-limit", Bindable.of(RateLimitProperties.class));
    }

    @Test
    void shipsAdr002sNumbersWithNothingConfigured() {
        RateLimitProperties limits = bind(Map.of());

        assertThat(limits.refuseRequests()).as("on unless explicitly switched off").isTrue();

        assertThat(limits.loginAttempts()).isEqualTo(20);
        assertThat(limits.loginWindow()).isEqualTo(Duration.ofMinutes(15));

        assertThat(limits.registrationsPerAddress()).isEqualTo(10);
        assertThat(limits.registrationsPerAddressWindow()).isEqualTo(Duration.ofHours(1));

        assertThat(limits.registrationEmailsPerRecipient()).isEqualTo(3);
        assertThat(limits.registrationEmailsPerRecipientWindow()).isEqualTo(Duration.ofHours(24));

        assertThat(limits.maxTrackedKeys()).isEqualTo(20_000);
    }

    /**
     * The incident switch, which is the one property anybody will ever set in
     * production, and the one the whole suite depends on.
     *
     * <p>It is named {@code refuse-requests} rather than {@code enabled}
     * because that is all it does. It was called {@code enabled} and also
     * disarmed the per-recipient mail cap, which meant the one situation it
     * exists for was also the situation in which flipping it turned
     * {@code /register} into a mail cannon.
     */
    @Test
    void canBeSwitchedOffWithoutRestatingEveryNumber() {
        RateLimitProperties limits = bind(Map.of("app.rate-limit.refuse-requests", "false"));

        assertThat(limits.refuseRequests()).isFalse();
        assertThat(limits.loginAttempts()).isEqualTo(20);
    }

    /**
     * And the cap it must not reach has no switch of its own to find. If a
     * {@code app.rate-limit.*} flag ever appears that disarms the mail cap,
     * this is the test that should have to be changed first.
     */
    @Test
    void offersNoWayToSwitchOffTheMailCap() {
        assertThat(RateLimitProperties.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "refuseRequests",
                        "loginAttempts", "loginWindow",
                        "registrationsPerAddress", "registrationsPerAddressWindow",
                        "registrationEmailsPerRecipient", "registrationEmailsPerRecipientWindow",
                        "maxTrackedKeys");
    }

    /**
     * A stale {@code app.rate-limit.*} variable must fail startup, not bind to
     * nothing.
     *
     * <p>Spring ignores unknown configuration properties, so renaming
     * {@code enabled} to {@code refuse-requests} left any environment still
     * setting {@code APP_RATE_LIMIT_ENABLED} silently un-switched -- the
     * limiter back on at the next deploy with nothing saying so.
     * {@code ignoreUnknownFields = false} turns that into a refused startup
     * naming the property, which is the answer this project gives for the
     * absent datasource and the absent signing key too.
     *
     * <p>An {@code ApplicationContextRunner} rather than the {@code Binder}
     * used above, because the handler that enforces this is applied by the
     * {@code @ConfigurationProperties} machinery and not by a bare bind.
     */
    @Test
    void refusesToStartOnAStaleRateLimitProperty() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindRateLimits.class)
                .withPropertyValues("app.rate-limit.enabled=false")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("app.rate-limit.enabled")
                        .hasMessageContaining("left unbound"));
    }

    @Test
    void startsNormallyOnThePropertiesItDoesDefine() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindRateLimits.class)
                .withPropertyValues(
                        "app.rate-limit.refuse-requests=false",
                        "app.rate-limit.registration-emails-per-recipient=100000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class BindRateLimits {
    }

    @Test
    void readsEachScopesCapacityAndWindowBackByScope() {
        RateLimitProperties limits = bind(Map.of());

        assertThat(limits.capacityOf(RateLimitScope.LOGIN_ATTEMPTS)).isEqualTo(20);
        assertThat(limits.capacityOf(RateLimitScope.REGISTRATIONS_PER_ADDRESS)).isEqualTo(10);
        assertThat(limits.capacityOf(RateLimitScope.REGISTRATION_EMAILS_PER_RECIPIENT)).isEqualTo(3);

        assertThat(limits.windowOf(RateLimitScope.LOGIN_ATTEMPTS))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(limits.windowOf(RateLimitScope.REGISTRATIONS_PER_ADDRESS))
                .isEqualTo(Duration.ofHours(1));
        assertThat(limits.windowOf(RateLimitScope.REGISTRATION_EMAILS_PER_RECIPIENT))
                .isEqualTo(Duration.ofHours(24));
    }
}
