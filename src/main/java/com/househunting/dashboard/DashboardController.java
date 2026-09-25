package com.househunting.dashboard;

import com.househunting.auth.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Placeholder for the signed-in area. Enough to prove authentication works;
 * the real dashboard arrives with listings management.
 *
 * <p>No explicit access check here: SecurityConfig's {@code anyRequest()
 * .authenticated()} already covers it, so a route is private unless it was
 * deliberately listed as public. Defaulting to closed means a forgotten route
 * fails safe.
 */
@Controller
public class DashboardController {

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        // @AuthenticationPrincipal injects whatever SessionLogin put in the
        // security context — our AuthenticatedUser record, which deliberately
        // holds no phone number.
        model.addAttribute("user", user);
        return "dashboard/index";
    }
}
