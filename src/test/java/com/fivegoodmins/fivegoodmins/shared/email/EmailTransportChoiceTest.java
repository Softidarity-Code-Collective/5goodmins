package com.fivegoodmins.fivegoodmins.shared.email;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which transport the application ends up with, which is three lines of
 * branching and the difference between a deployed site that sends mail, one
 * that fails loudly, and one that quietly sends none.
 *
 * <p>Worth its own test because every branch is reached by a <em>different
 * environment</em> and none of them is the one the suite runs in: production
 * has an API key, a developer machine has a mail host, and the deployed site
 * had neither for several weeks. A test that only covered the configuration it
 * runs under would cover the branch nobody uses.
 *
 * <p>The bean method is called directly rather than through an
 * {@code ApplicationContextRunner}. {@link EmailConfig} also declares the
 * text-mode template resolver, so a context would have to bring Thymeleaf's
 * auto-configuration along to assert something about mail.
 */
class EmailTransportChoiceTest {

    private static EmailProperties properties(String apiKey) {
        return new EmailProperties(
                "no-reply@5goodmins.com",
                "5 Good Minutes",
                "https://5goodmins.com",
                new EmailProperties.Brevo(apiKey, Duration.ofSeconds(5), Duration.ofSeconds(10)));
    }

    private EmailSender chosen(String apiKey, JavaMailSender smtp) {
        return new EmailConfig().emailSender(provider(smtp), properties(apiKey));
    }

    @Test
    void usesBrevoWhenAnApiKeyIsConfigured() {
        assertThat(chosen("a-real-key", null)).isInstanceOf(BrevoEmailSender.class);
    }

    /**
     * application-dev.yaml sets {@code spring.mail.host} for every local run,
     * so a mail host is not evidence of an intention; an API key only exists
     * because somebody put it there. Brevo therefore wins.
     */
    @Test
    void prefersBrevoOverSmtpWhenBothAreConfigured() {
        assertThat(chosen("a-real-key", new JavaMailSenderImpl()))
                .isInstanceOf(BrevoEmailSender.class);
    }

    @Test
    void usesSmtpWhenThereIsNoApiKeyButThereIsAMailHost() {
        assertThat(chosen(null, new JavaMailSenderImpl())).isInstanceOf(SmtpEmailSender.class);
    }

    /**
     * A key set to the empty string -- an environment variable declared and
     * left blank, which is how a deploy platform renders "I meant to fill this
     * in". Treating it as configured would give a sender that 401s on every
     * message instead of one that says at startup that nothing is set up.
     */
    @Test
    void treatsABlankApiKeyAsNoApiKey() {
        assertThat(chosen("   ", new JavaMailSenderImpl())).isInstanceOf(SmtpEmailSender.class);
        assertThat(chosen("", null)).isInstanceOf(UnconfiguredEmailSender.class);
    }

    /**
     * The state the deployed site was in for weeks: no transport at all. It
     * must start and serve every page, and refuse loudly on a send rather than
     * discard silently (ADR-005).
     */
    @Test
    void refusesLoudlyWhenNothingIsConfigured() {
        assertThat(chosen(null, null)).isInstanceOf(UnconfiguredEmailSender.class);
    }

    private static ObjectProvider<JavaMailSender> provider(JavaMailSender value) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getIfAvailable() {
                return value;
            }
        };
    }
}
