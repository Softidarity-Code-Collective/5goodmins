package com.fivegoodmins.fivegoodmins.shared.security;

import com.fivegoodmins.fivegoodmins.action.ActionPaths;
import com.fivegoodmins.fivegoodmins.shared.AuthPaths;
import com.fivegoodmins.fivegoodmins.shared.ratelimit.RateLimitFilter;
import com.fivegoodmins.fivegoodmins.shared.ratelimit.RateLimitProperties;
import com.fivegoodmins.fivegoodmins.shared.ratelimit.RateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * The web security configuration (ADR-002): form login over a server-side
 * session, one role per user, BCrypt through a {@link PasswordEncoder} that can
 * read any encoding it has an id for.
 *
 * <p>This file is deliberately the first thing written after the entities and
 * before the first controller. {@code spring-boot-starter-security} is on the
 * classpath, and with no {@link SecurityFilterChain} bean Spring Boot installs
 * its own: every path behind form login, against one generated in-memory user
 * whose password is printed to the log at startup. That is not a safe default to
 * add controllers on top of -- the first endpoint would inherit it silently.
 *
 * <p>Spring Security 7 DSL only: lambda configurers, no {@code .and()} chaining,
 * no {@code WebSecurityConfigurerAdapter}. Both were removed, not deprecated, so
 * every Boot 3 example needs translating (ADR-018).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            RateLimiter rateLimiter,
                                            RateLimitProperties rateLimits) throws Exception {
        http
                // ADR-002's rate limiting, on POST /login and POST /register.
                // Before CsrfFilter, so a spray with no token is refused before
                // it costs a session lookup; see RateLimitFilter for the cost
                // of that ordering and for why this is constructed here rather
                // than injected as a bean.
                .addFilterBefore(new RateLimitFilter(rateLimiter, rateLimits.refuseRequests()),
                        CsrfFilter.class)

                .authorizeHttpRequests(authorize -> authorize
                        // Static assets. Listed explicitly rather than through
                        // PathRequest.toStaticResources(): that helper matches
                        // Boot's conventional locations, and an "everything
                        // under these four prefixes is public" rule I wrote
                        // myself is one I can read off the file.
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico")
                        .permitAll()

                        // Spring Boot's error page. Only reachable on a direct
                        // request: the ERROR dispatch is not filtered by
                        // default, so a forward to /error never reaches this
                        // rule. Without it, a direct GET /error would redirect
                        // an anonymous visitor to the login form.
                        .requestMatchers("/error").permitAll()

                        // The anonymous half of ADR-002's registration flow,
                        // named one by one. /verify-email is the path in every
                        // verification email already in a mailbox, and
                        // /register/check-email is the GET half of the form's
                        // POST -- leaving either out would redirect a reader to
                        // the login form they do not yet have an account for,
                        // and the second would take the flash attribute with it.
                        .requestMatchers(
                                AuthPaths.REGISTER,
                                AuthPaths.CHECK_EMAIL,
                                AuthPaths.VERIFY_EMAIL,
                                // The consent checkbox on /register links here,
                                // so it has to be readable before anybody has
                                // an account -- a privacy policy behind a login
                                // is not one you can consent to.
                                AuthPaths.PRIVACY,
                                // The action listing. Public because the site
                                // is a public directory, and because the
                                // welcome email links here: a user who has
                                // just confirmed their address is usually not
                                // signed in yet, and sending them to a login
                                // form is how that email had five dead links
                                // and one misleading one.
                                ActionPaths.LIST)
                        .permitAll()

                        // Default deny. Most of this site will end up public --
                        // it is a directory people are meant to browse without
                        // an account -- but the public paths get named one by
                        // one as the controllers that serve them are written.
                        // A forgotten endpoint then returns a login redirect
                        // rather than data.
                        .anyRequest().authenticated())

                // formLogin is not one of the configurers HttpSecurity
                // applies by default, so without this line there is no way to
                // authenticate at all. logout, csrf, headers,
                // sessionManagement and exceptionHandling *are* applied by
                // default, which is why they are absent here:
                //
                //   * CSRF protection is on. It will 403 htmx POSTs until the
                //     token is wired into hx-headers (ADR-002); that is a
                //     template change, not a reason to disable it here.
                //   * Session fixation protection is on, as changeSessionId.
                //   * The default headers are HSTS, X-Content-Type-Options:
                //     nosniff, X-Frame-Options: DENY and a no-store
                //     Cache-Control.
                //
                // HSTS is the one that needed a fix outside this class.
                // Spring Security writes Strict-Transport-Security only when
                // request.isSecure() is true, and behind Cloudflare the TLS
                // terminates at the edge: the request Tomcat sees is plain
                // HTTP, so the header was measurably absent in production.
                // The fix is server.forward-headers-strategy=native in
                // application.yaml, which makes Tomcat read X-Forwarded-Proto
                // and report the request as secure -- not a headers() block
                // here, because overriding the condition would make the header
                // a lie on any request that really is plain HTTP.
                //
                // loginPage() names LoginController's template rather than
                // Spring Security's generated one. Two consequences follow and
                // neither is automatic: DefaultLoginPageGeneratingFilter stops
                // answering, so the path has to be permitted explicitly -- it
                // is, by permitAll() below, which also covers the POST the form
                // makes to it -- and the failure redirect becomes
                // /login?error, which is the parameter LoginController renders
                // one deliberately vague message for.
                .formLogin(form -> form
                        .loginPage(AuthPaths.LOGIN)
                        .permitAll());

        return http.build();
    }

    /**
     * {@code DelegatingPasswordEncoder}: BCrypt for new passwords, and every
     * other encoder Spring Security knows kept available for verification. The
     * stored value carries its own algorithm as a {@code {bcrypt}} prefix, so
     * moving to Argon2id later means changing the default id here and letting
     * existing hashes verify under their old prefix -- no migration, no
     * flag-day, no column change (app_user.password_hash is varchar(255) for
     * exactly this reason).
     *
     * <p>Never {@code new BCryptPasswordEncoder()} directly: a bare encoder
     * stores an unprefixed hash, and then the algorithm is a fact about the
     * code at the time of writing rather than about the row.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * ADMIN > POSTER > USER (ADR-002). One role per user, in a single column,
     * so a hierarchy is what lets {@code hasRole("USER")} hold for an admin
     * without every rule listing all three.
     *
     * <p>Publishing the bean is enough for {@code authorizeHttpRequests}:
     * {@code AuthorizeHttpRequestsConfigurer} looks a {@link RoleHierarchy}
     * bean up from the context and hands it to the
     * {@code DefaultAuthorizationManagerFactory} behind {@code hasRole}.
     * Method security is separate -- {@code @EnableMethodSecurity} needs its own
     * {@code MethodSecurityExpressionHandler} bean carrying this hierarchy, and
     * that goes in when the first {@code @PreAuthorize} does, not before.
     *
     * <p>{@code static}, as the reference documentation has it: the bean is then
     * available without instantiating this configuration class, which is what
     * keeps a method-security expression handler from forcing the whole class to
     * initialize early.
     *
     * <p>{@code withDefaultRolePrefix()} prepends {@code ROLE_}, so the strings
     * here stay the bare role names that {@link com.fivegoodmins.fivegoodmins.user.Role}
     * and the app_user.role column use. {@code setHierarchy(String)} was removed
     * in Spring Security 7; the builder is the replacement.
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN").implies("POSTER")
                .role("POSTER").implies("USER")
                .build();
    }
}
