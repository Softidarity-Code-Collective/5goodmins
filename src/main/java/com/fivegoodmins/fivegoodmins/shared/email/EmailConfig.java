package com.fivegoodmins.fivegoodmins.shared.email;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Wires the one {@link EmailSender}, the pool that sends on, and the resolver
 * that finds the plain-text half of an email.
 */
@Configuration(proxyBeanMethods = false)
public class EmailConfig {

    /**
     * The mail pool's bean name, for the {@code @Qualifier} that picks it out
     * from Boot's {@code applicationTaskExecutor}.
     */
    public static final String EMAIL_EXECUTOR = "emailExecutor";

    /**
     * One thread, and a bounded queue in front of it.
     *
     * <p><strong>Single-threaded on purpose, and stated as one thread rather
     * than two.</strong> An earlier version of this said "core 1, max 2", which
     * is a configuration that reads as a two-thread pool and is not one:
     * {@code ThreadPoolExecutor} grows past its core size only when the queue
     * is <em>full</em>, so with fifty slots in front of it the second thread
     * appears on the fifty-first simultaneous send and never otherwise. Naming
     * a maximum above the core size with a non-trivial queue describes a pool
     * that does not exist.
     *
     * <p>One thread is also the right answer rather than merely the honest one.
     * The work is one SMTP or HTTP round trip per task at registration rates,
     * plus one nightly digest run, and sending serially costs nothing anyone
     * waits for -- nothing holds a request open. If the digest ever needs
     * concurrency, the change is to raise <em>both</em> numbers together, not
     * to raise the maximum and expect it to be used.
     *
     * <p><strong>The bounded queue is the reason this pool exists at all.</strong>
     * Boot's {@code applicationTaskExecutor} defaults to an unbounded one, so a
     * mail provider that stops responding would accumulate tasks until the heap
     * went, with the symptom surfacing somewhere unrelated because that pool
     * serves everything. Fifty is deliberately small: it is a buffer for a
     * provider that is briefly slow, not a queue that is meant to absorb an
     * outage. Past it, the default abort policy rejects, which
     * {@code RegistrationEmailListener} catches and logs with the account it
     * was for.
     *
     * <p>Shutdown waits, briefly. A deploy that interrupts a send mid-flight
     * drops a verification email the user is already waiting for, and with no
     * resend form (ADR-002) their recovery is to register again. Ten seconds is
     * enough for a queue this short and short enough not to hold a container
     * open.
     */
    @Bean(EMAIL_EXECUTOR)
    AsyncTaskExecutor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        // Named, because the one thing anyone ever wants from a thread dump is
        // to know whose thread it is.
        executor.setThreadNamePrefix("email-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    /**
     * The transport, chosen once at startup: Brevo's HTTP API when an API key
     * is configured, SMTP when a mail host is, and a sender that refuses
     * loudly when neither is.
     *
     * <p><strong>Brevo wins when both are set, and that order is deliberate.</strong>
     * {@code spring.mail.host} is set for everyone by application-dev.yaml, so
     * it is not evidence of an intention; an API key is only ever present
     * because somebody put it there. The practical effect is that a developer
     * who exports the production key locally sends real mail, which is
     * surprising exactly once and is the honest reading of what they asked for.
     *
     * <p>One {@code @Bean} method with a branch, rather than three beans with
     * conditions on them. {@code @ConditionalOnMissingBean} is documented as
     * being for auto-configuration and is evaluated in bean-definition
     * registration order, so a pair of conditional beans in application code
     * is a choice that depends on scanning order -- which is exactly the kind
     * of thing that works until a package is renamed. A branch on whether
     * Boot's own mail auto-configuration produced a {@code JavaMailSender} is
     * deterministic, and it reads as what it is.
     *
     * <p>{@code ObjectProvider} rather than a nullable parameter: that bean
     * exists only when {@code spring.mail.host} is set, which is the condition
     * Boot's {@code MailSenderAutoConfiguration} itself uses. Asking for it
     * directly would make the context fail to start wherever mail is not
     * configured -- and that is production until the provider is chosen (§5).
     */
    @Bean
    EmailSender emailSender(ObjectProvider<JavaMailSender> transport, EmailProperties properties) {
        if (properties.brevo().isConfigured()) {
            return new BrevoEmailSender(RestClient.builder().requestFactory(timeouts(properties)),
                    properties);
        }
        JavaMailSender smtp = transport.getIfAvailable();
        return smtp == null ? new UnconfiguredEmailSender() : new SmtpEmailSender(smtp, properties);
    }

    /**
     * Connect and read timeouts on the provider call, applied here rather than
     * inside {@link BrevoEmailSender} so that a test can hand that class a
     * builder bound to {@code MockRestServiceServer} without this clobbering
     * the mock's request factory.
     *
     * <p><strong>{@code RestClient.builder()} rather than Boot's
     * auto-configured {@code RestClient.Builder} bean, which does not exist
     * here.</strong> Boot 4 moved that auto-configuration into a
     * {@code spring-boot-restclient} module which {@code -starter-webmvc} does
     * not pull in, so asking for the bean fails the context at startup -- found
     * by doing exactly that. Adding the module to get back a builder whose
     * customizers nothing in this application uses would be a dependency for a
     * one-line call. One {@code RestClient} exists here, it is built once, and
     * this is the only place that configures it.
     *
     * <p>{@code SimpleClientHttpRequestFactory} -- the JDK's
     * {@code HttpURLConnection} -- rather than a pooled client. One email at a
     * time from one thread needs no connection pool, and the alternative is a
     * dependency to justify (ADR-018's standing rule) for a call that happens
     * a few hundred times a day.
     */
    private static ClientHttpRequestFactory timeouts(EmailProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.brevo().connectTimeout());
        factory.setReadTimeout(properties.brevo().readTimeout());
        return factory;
    }

    /**
     * Resolves {@code email/*.txt} in {@link TemplateMode#TEXT}, so the
     * plain-text part of an email is a template rather than string
     * concatenation in Java.
     *
     * <p>Boot's auto-configured resolver is HTML-only -- one resolver has one
     * template mode -- and a second one is how Thymeleaf is meant to serve two:
     * {@code SpringTemplateEngine} is handed every {@code ITemplateResolver}
     * bean in the context. The alternative was building the text body in Java
     * from bundle keys, which puts the layout of a user-facing document in
     * source code and splits one email's content across two places.
     *
     * <p>The two resolvers cannot shadow each other, and not by luck. This one
     * answers only names matching {@code email/*.txt}; the HTML one appends
     * {@code .html}, finds nothing for such a name, and declines
     * ({@code spring.thymeleaf.check-template} is true by default, which is
     * what makes declining possible rather than fatal). So the order they are
     * consulted in does not matter -- the explicit order below is documentation,
     * not a dependency.
     *
     * <p>Prefix and caching come from {@link ThymeleafProperties} so that
     * {@code spring.thymeleaf.cache=false} in development reloads both halves
     * of an email, not just the HTML.
     */
    @Bean
    SpringResourceTemplateResolver emailTextTemplateResolver(ApplicationContext applicationContext,
                                                            ThymeleafProperties properties) {
        SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
        resolver.setApplicationContext(applicationContext);
        resolver.setPrefix(properties.getPrefix());
        // No suffix: the template name carries ".txt" itself, which is what
        // keeps "email/verification" (HTML) and "email/verification.txt" (text)
        // two distinct names rather than one name two resolvers both claim.
        resolver.setSuffix("");
        resolver.setResolvablePatterns(Set.of("email/*.txt"));
        resolver.setTemplateMode(TemplateMode.TEXT);
        // UTF-8 stated rather than inherited from spring.thymeleaf.encoding:
        // the content is Cyrillic, so this is a setting with exactly one
        // correct value, and the property is nullable.
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(properties.isCache());
        resolver.setCheckExistence(true);
        resolver.setOrder(1);
        return resolver;
    }
}
