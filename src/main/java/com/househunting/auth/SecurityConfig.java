package com.househunting.auth;

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

                        // Everything else — dashboard, wizard, requests —
                        // requires a session. `anyRequest()` last means a new
                        // route is private by default: forgetting to list it
                        // locks it down rather than exposing it.
                        .anyRequest().authenticated()
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
