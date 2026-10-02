package com.nexttenant.legal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;

/**
 * The privacy policy.
 *
 * <p>Public, and deliberately so: somebody deciding whether to hand over a
 * phone number needs to be able to read this BEFORE they have an account, and
 * a policy behind a login is a policy nobody reads at the only moment it
 * matters.
 */
@Controller
public class LegalController {

    /**
     * When the policy last changed, in the policy itself.
     *
     * <p>A constant rather than a build timestamp or {@code now()}. A date
     * that moves on every deploy tells a returning reader the terms changed
     * when they did not, and a reader who stops believing the date stops
     * reading the page. This moves by hand, when the words move.
     */
    private static final LocalDate LAST_UPDATED = LocalDate.of(2026, 10, 2);

    private final LegalProperties legal;

    public LegalController(LegalProperties legal) {
        this.legal = legal;
    }

    @GetMapping("/privacy")
    public String privacy(Model model) {
        model.addAttribute("legal", legal);
        model.addAttribute("lastUpdated", LAST_UPDATED);
        return "legal/privacy";
    }
}
