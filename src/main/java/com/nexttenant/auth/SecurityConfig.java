package com.nexttenant.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * The security filter chain.
 *
 * <p>Worth knowing what adding {@code spring-boot-starter-security} does on its
 * own, before any configuration: every URL requires authentication, a login
 * form is published at /login, and a random password is printed to the log at
 * startup. That is a sensible default for an app that has not thought about
 * security, and entirely wrong for one that authenticates by OTP — so much of
 * this class is switching things off.
 *
 * <p>Two defaults are kept deliberately: CSRF protection and session fixation
 * protection. Both are explained below.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Public: the whole browse experience works signed out.
                        // Requiring a login to look at listings would cost most
                        // of the organic search traffic the product depends on.
                        .requestMatchers("/", "/flats", "/flats/**").permitAll()
                        .requestMatchers("/login", "/login/**", "/logout").permitAll()
                        // Photos are public: a listing page shows them to
                        // anyone. Serving is gated on processed_at, so an
                        // unstripped image is never reachable here.
                        .requestMatchers("/photos/**").permitAll()
                        .requestMatchers("/css/**", "/js/**", "/images/**",
                                         "/favicon.ico", "/robots.txt", "/sitemap.xml").permitAll()
                        .requestMatchers("/error").permitAll()

                        // The load balancer's health check. Only /health is
                        // opened — the other actuator endpoints expose
                        // configuration, environment variables and beans, and
                        // publishing those is how a deployment leaks its own
                        // database URL.
                        .requestMatchers("/actuator/health").permitAll()

                        // Everything else under /actuator is ADMIN, and this
                        // line has to exist. Without it those endpoints fall
                        // through to anyRequest().authenticated() — so any
                        // signed-in user could read the metrics and change log
                        // levels. Harmless while only `health` was exposed,
                        // and a hole the moment anything else was.
                        //
                        // What is exposed is decided in application.yml, and
                        // `env` and `configprops` are deliberately not: they
                        // print resolved configuration, which includes the
                        // database password.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // Moderation. Declared BEFORE anyRequest(), and by
                        // role rather than by a check inside each handler, so
                        // an /admin route added later is protected by default.
                        .requestMatchers("/admin/**").hasRole("ADMIN")

                        // Everything else — dashboard, wizard, requests —
                        // requires a session. `anyRequest()` last means a new
                        // route is private by default: forgetting to list it
                        // locks it down rather than exposing it.
                        .anyRequest().authenticated()
                )

                // ---- Response headers ------------------------------------
                //
                // Spring Security sets sensible defaults (nosniff, frame deny,
                // no-cache on authenticated responses). These are the three it
                // cannot guess for us.
                .headers(headers -> headers

                        // HSTS: after the first visit over HTTPS, the browser
                        // refuses to speak plain HTTP to this host at all —
                        // which closes the window where a redirect from
                        // http:// can be intercepted before the redirect
                        // happens.
                        //
                        // Deliberately NOT preload, and not includeSubDomains.
                        // Preloading is baked into browsers and effectively
                        // irreversible; committing to it before the domain and
                        // its subdomains are settled is a decision that cannot
                        // be taken back in a hurry.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(false)
                                .maxAgeInSeconds(31536000))

                        // Referrer: send the full URL within our own site, and
                        // only the origin to anyone else. Without this, a
                        // visitor clicking a Google Maps tile would hand
                        // Google the exact listing URL they were looking at.
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers
                                        .ReferrerPolicyHeaderWriter.ReferrerPolicy
                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))

                        // CSP: the real defence against an injected script.
                        // Everything of ours is same-origin; the exceptions are
                        // listed because they are the only third parties this
                        // site loads at all.
                        //
                        // 'unsafe-inline' is present for styles and NOT for
                        // scripts. Inline styles are used by the templates;
                        // inline script is what an injection attack needs, so
                        // that is the one that must not be allowed.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(String.join("; ",
                                "default-src 'self'",
                                "script-src 'self' https://maps.googleapis.com "
                                        + "https://unpkg.com",
                                "style-src 'self' 'unsafe-inline' "
                                        + "https://fonts.googleapis.com https://unpkg.com",
                                "font-src 'self' https://fonts.gstatic.com",
                                // blob: and data: are how the wizard previews a
                                // photo before it has been uploaded.
                                //
                                // BOTH forms of the tile host are listed, and
                                // that is not belt-and-braces. A CSP wildcard
                                // requires at least one label in front of it,
                                // so `*.tile.openstreetmap.org` matches
                                // `a.tile.openstreetmap.org` and does NOT match
                                // `tile.openstreetmap.org` — which is exactly
                                // the URL our Leaflet layers request. With only
                                // the wildcard, every tile was blocked and the
                                // map rendered as a grey rectangle.
                                //
                                // unpkg.com is here because Leaflet's own
                                // marker images are served from beside its CSS.
                                // Allowing the stylesheet and not the images it
                                // references gives a working map with a broken
                                // pin — which looks like a bug in our code.
                                "img-src 'self' data: blob: "
                                        + "https://tile.openstreetmap.org "
                                        + "https://*.tile.openstreetmap.org "
                                        + "https://unpkg.com "
                                        + "https://*.googleapis.com https://*.gstatic.com",
                                "connect-src 'self' https://maps.googleapis.com "
                                        + "https://tile.openstreetmap.org "
                                        + "https://*.tile.openstreetmap.org",
                                // Nothing on this site should ever be framed,
                                // and this is the modern form of that rule.
                                "frame-ancestors 'none'",
                                "base-uri 'self'",
                                // Forms may only post back to us — so an
                                // injected form cannot exfiltrate a CSRF token
                                // or a typed phone number to another host.
                                "form-action 'self'")))
                )

                // No username/password form: there are no passwords. Leaving it
                // enabled would publish a login page that can never succeed.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                // CSRF stays ON.
                //
                // Without it, an attacker's page can contain a form posting to
                // our site and submit it automatically. The browser attaches the
                // session cookie — cookies are sent by destination, not by
                // origin — and the server sees a fully authenticated request to
                // delete a listing or accept a contact request.
                //
                // The defence is a token rendered into every form and checked on
                // every POST. The attacker's page cannot read it, because the
                // browser will not let one origin read another's HTML.
                // Thymeleaf inserts it into every <form method="post"> with no
                // markup needed.
                .csrf(csrf -> {
                })

                .sessionManagement(session -> session
                        // Session fixation protection, kept on.
                        //
                        // The attack: an attacker gets a session id (visiting
                        // the site gives them one), tricks the victim into using
                        // it, and waits for the victim to log in. If the id does
                        // not change at login, the attacker's copy is now an
                        // authenticated session.
                        //
                        // changeSessionId() issues a new id at authentication and
                        // keeps the session's contents, so the attacker holds a
                        // dead id.
                        .sessionFixation(fixation -> fixation.changeSessionId())

                        // One session id per browser; a user may be signed in on
                        // several devices, which is normal for a phone-plus-laptop
                        // audience.
                        .maximumSessions(5)
                )

                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        // Destroys the server-side session, so the row in
                        // SPRING_SESSION is deleted and the id cannot be reused.
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("SESSION")
                )

                .exceptionHandling(ex -> ex
                        // An unauthenticated request to a private page goes to
                        // the login screen rather than returning a bare 403.
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"))
                );

        return http.build();
    }

    /**
     * Explicit since Spring Security 6.
     *
     * <p>Earlier versions saved the SecurityContext to the session
     * automatically. They no longer do, which is a common upgrade surprise:
     * programmatic login appears to work for the rest of the request and the
     * user is anonymous again on the next one. Our login code saves through
     * this bean deliberately.
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
