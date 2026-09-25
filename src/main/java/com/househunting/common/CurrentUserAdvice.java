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

    @ModelAttribute("currentUser")
    public AuthenticatedUser currentUser(@AuthenticationPrincipal AuthenticatedUser user) {
        // Null when signed out; templates use th:if to branch.
        return user;
    }
}
