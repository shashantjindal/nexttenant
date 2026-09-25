package com.househunting.home;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the public landing page.
 *
 * <p>{@code @Controller} (not {@code @RestController}) means a returned String is a
 * VIEW NAME, resolved to {@code src/main/resources/templates/<name>.html}.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String landing(Model model) {
        // `Model` is the one-way channel from controller to template.
        // Templates can only read what is put here — they cannot reach into
        // services or the database. That constraint is deliberate: it keeps the
        // view dumb, and it is the same mechanism that will later stop a phone
        // number reaching a page that should not show one.
        model.addAttribute("phase", "Phase 1 — toolchain verified");
        return "home/landing";
    }
}
