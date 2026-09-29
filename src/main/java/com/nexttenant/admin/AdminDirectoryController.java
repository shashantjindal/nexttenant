package com.nexttenant.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Browsing accounts and listings.
 *
 * <p>Read-only, deliberately. Changing things — suspending an account,
 * removing a listing — already has a home in {@code ModerationService}, where
 * each action is recorded against the moderator who took it. A second route to
 * the same effects, without that record, would make the audit trail a
 * best-effort thing rather than a fact.
 *
 * <p>The listings page does carry a "Take down" link, and it points at
 * {@code AdminController} in the moderation package rather than at anything
 * here. That is the same rule holding rather than an exception to it: the
 * browsing lives with browsing, the decision lives with the audit log that
 * records it.
 *
 * <p>Protected by {@code SecurityConfig}'s {@code /admin/**} rule, like every
 * other route under this prefix.
 */
@Controller
public class AdminDirectoryController {

    private final AdminDirectory directory;

    public AdminDirectoryController(AdminDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/admin/users")
    public String users(@RequestParam(value = "q", required = false) String search,
                        @RequestParam(value = "page", defaultValue = "0") int page,
                        Model model) {
        int safePage = Math.max(0, page);
        var rows = directory.accounts(search, safePage);

        model.addAttribute("rows", rows);
        model.addAttribute("search", search);
        model.addAttribute("page", safePage);
        // A full count on every page load is a second query for a number
        // nobody acts on. A full page means there is probably another.
        model.addAttribute("hasMore", rows.size() == AdminDirectory.PAGE);
        return "admin/users";
    }

    @GetMapping("/admin/listings")
    public String listings(@RequestParam(value = "status", required = false) String status,
                           @RequestParam(value = "page", defaultValue = "0") int page,
                           Model model) {
        int safePage = Math.max(0, page);
        var rows = directory.listings(status, safePage);

        model.addAttribute("rows", rows);
        model.addAttribute("status", status);
        model.addAttribute("page", safePage);
        model.addAttribute("hasMore", rows.size() == AdminDirectory.PAGE);
        return "admin/listings";
    }
}
