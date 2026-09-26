package com.househunting.common;

import com.househunting.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Makes the signed-in user available to every template as {@code currentUser}.
 *
 * <p>{@code @ControllerAdvice} applies to all controllers, so the header does
 * not depend on each one remembering to add the attribute — and a page that
 * forgot would silently render as signed-out.
 *
 * <p>Done this way rather than with thymeleaf-extras-springsecurity: one small
 * class against a dependency, and the value reaching the view is our own record
 * rather than whatever Spring Security happens to hold.
 */
@ControllerAdvice
public class CurrentUserAdvice {

    private final com.househunting.shortlist.ShortlistService shortlists;

    public CurrentUserAdvice(com.househunting.shortlist.ShortlistService shortlists) {
        this.shortlists = shortlists;
    }

    @ModelAttribute("currentUser")
    public AuthenticatedUser currentUser(@AuthenticationPrincipal AuthenticatedUser user) {
        // Null when signed out; templates use th:if to branch.
        return user;
    }

    /**
     * The path and query of the current page, for "bring me back here" forms.
     *
     * <p>The heart on a listing card posts and redirects, and the redirect has
     * to land on the page the heart was clicked from — including its filters
     * and its scroll position in the URL. The template cannot work this out for
     * itself: Thymeleaf 3.1 removed {@code #httpServletRequest} and the other
     * direct servlet-API accessors precisely so that templates stop reaching
     * into request internals.
     *
     * <p>Only the path and query are exposed, never the scheme or host. The
     * value is echoed back as a redirect target, and a redirect target that can
     * carry a host is an open redirect waiting to be found. The controller
     * checks again that it starts with a single "/" — two checks, because this
     * one is about what we hand out and that one is about what we accept, and
     * either could be bypassed alone.
     */
    /**
     * How many rooms are on the signed-in person's wishlist.
     *
     * <p>A count on the header button, so hearting something on a search page
     * visibly lands somewhere. Without it the heart fills in and nothing else
     * changes, which reads as "did that do anything?" — the complaint this is
     * answering.
     *
     * <p>It is one indexed COUNT on a two-column join table, and only for
     * signed-in visitors: anonymous requests, which are most of them, do no
     * query at all. Worth stating plainly that this is a query per page render
     * — if the header ever grows a second such badge, they should be fetched
     * together rather than one advice method each.
     */
    @ModelAttribute("wishlistCount")
    public Long wishlistCount(@AuthenticationPrincipal AuthenticatedUser user) {
        return user == null ? null : shortlists.countFor(user.id());
    }

    @ModelAttribute("currentPath")
    public String currentPath(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        return query == null || query.isBlank() ? path : path + "?" + query;
    }
}
