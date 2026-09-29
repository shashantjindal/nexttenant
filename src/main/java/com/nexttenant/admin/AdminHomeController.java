package com.nexttenant.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The admin landing page.
 *
 * <p>Exists because {@code /admin} was a 404 while {@code /admin/reports}
 * worked — so the moderation queue was reachable only by somebody who already
 * knew its URL, and typing the obvious address got nothing. An operator should
 * be able to guess where they are going.
 *
 * <p>No security annotation here on purpose. {@code SecurityConfig} matches
 * {@code /admin/**} to {@code hasRole("ADMIN")} before {@code anyRequest()},
 * so a route added under that prefix is protected by default and forgetting an
 * annotation cannot expose one. The rule lives in one place rather than being
 * repeated, and inconsistently, across handlers.
 */
@Controller
public class AdminHomeController {

    private final AdminOverviewService overview;

    public AdminHomeController(AdminOverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/admin")
    public String home(Model model) {
        model.addAttribute("overview", overview.snapshot());
        return "admin/index";
    }
}
