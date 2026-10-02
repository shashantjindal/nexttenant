package com.nexttenant.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Holds the response open long enough for a page to finish rendering.
 *
 * <h2>The bug this fixes</h2>
 *
 * <p>Tomcat buffers 8KB of a response and then COMMITS it: the status line and
 * headers go out, and nothing about the response may be changed afterwards.
 * That is normally invisible. It stopped being invisible when the critical CSS
 * was inlined into {@code layout/base.html}, because the {@code <head>} alone
 * then exceeded 8KB — so every page committed before its body had rendered.
 *
 * <p>Spring Security 6 loads the CSRF token lazily: it is created the first
 * time something asks for it, which for a server-rendered page is the moment
 * Thymeleaf renders a {@code <form>} with {@code th:action}. Creating that
 * token creates the session. On the login page the form is well past the first
 * 8KB, so the sequence became:
 *
 * <ol>
 *   <li>head renders, exceeds the buffer, response commits</li>
 *   <li>form renders, asks for the CSRF token</li>
 *   <li>Spring Session tries to create a session, and cannot — the response is
 *       already committed</li>
 *   <li>{@code IllegalStateException} mid-render: the connection closes with a
 *       half-written document, and the browser shows a white page</li>
 * </ol>
 *
 * <p>It affected anonymous visitors on any page with a POST form — which is
 * the login page, so it broke every sign-up.
 *
 * <h2>Why this fix and not the others</h2>
 *
 * <p><b>Not eager CSRF tokens.</b> Spring Security can be told to load the
 * token on every request, which would also fix it. It would also create a
 * session for every anonymous visitor — including every crawler hit — and
 * sessions here live in Postgres. That is a row written per request to make a
 * rendering order problem go away.
 *
 * <p><b>Not just a smaller critical.css.</b> Keeping the head under 8KB works
 * until somebody adds a rule. The failure it causes is a white page on the
 * login screen, which is the worst possible place for a bug nobody can see
 * coming from a CSS edit.
 *
 * <p>A larger buffer addresses the actual constraint. 64KB comfortably holds
 * any page this application renders, so the response commits when the view is
 * finished rather than part way through it.
 *
 * <p>The cost is memory per concurrent response, which at this scale is
 * nothing: 64KB times a few dozen in-flight requests on a 2GB box.
 */
@Configuration
public class ResponseBufferConfig {

    /**
     * Big enough for the largest page here with room to spare.
     *
     * <p>The listing detail page is the biggest at roughly 25KB; a search page
     * of twenty cards is similar. 64KB leaves headroom without being a number
     * anybody has to think about again.
     */
    static final int BUFFER_BYTES = 64 * 1024;

    /**
     * Registered FIRST, ahead of Spring Security.
     *
     * <p>{@code setBufferSize} is only legal before anything has been written,
     * so this has to run before any filter that might write — and the security
     * chain writes redirects and error responses.
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> responseBufferFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration =
                new FilterRegistrationBean<>(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request,
                                                    HttpServletResponse response,
                                                    FilterChain chain)
                            throws ServletException, IOException {
                        // Tomcat ignores a request to SHRINK the buffer and
                        // throws if anything has been written, so this is only
                        // ever a widening and only ever at the very start.
                        if (response.getBufferSize() < BUFFER_BYTES) {
                            response.setBufferSize(BUFFER_BYTES);
                        }
                        chain.doFilter(request, response);
                    }
                });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("responseBufferFilter");
        return registration;
    }
}
