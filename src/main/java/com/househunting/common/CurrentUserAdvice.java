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
    private final com.househunting.request.RequestService requests;

    public CurrentUserAdvice(com.househunting.shortlist.ShortlistService shortlists,
                             com.househunting.request.RequestService requests) {
        this.shortlists = shortlists;
        this.requests = requests;
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
     * The numbers on the header's badges.
     *
     * <p>The wishlist count came first, so that hearting something on a search
     * page visibly lands somewhere — without it the heart fills in and nothing
     * else changes, which reads as "did that do anything?".
     *
     * <p>The pending-request count is the same argument with more at stake. A
     * request that nobody notices is the failure mode that kills a marketplace
     * of this shape: somebody asks, nobody answers, both sides conclude the
     * site is dead. A number on the header is the cheapest possible way to
     * make an unanswered request visible on every page rather than only on the
     * one nobody opens.
     *
     * <p>Two indexed counts, and only for signed-in visitors — anonymous
     * requests, which are most of them, do no query at all. See
     * {@link HeaderCounts} for why they are bundled rather than merged into a
     * single statement.
     */
    @ModelAttribute("counts")
    public HeaderCounts counts(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            return HeaderCounts.NONE;
        }
        return new HeaderCounts(
                shortlists.countFor(user.id()),
                requests.pendingCountFor(user.id()),
                requests.answeredUnseenCountFor(user.id()));
    }

    @ModelAttribute("currentPath")
    public String currentPath(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        return query == null || query.isBlank() ? path : path + "?" + query;
    }
}
