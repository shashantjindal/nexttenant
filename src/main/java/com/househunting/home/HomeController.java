package com.househunting.home;

import com.househunting.common.Rupees;
import com.househunting.listing.ListingCatalog;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The public landing page.
 *
 * <p>Thin by design: it fetches, puts values on the model and names a view.
 * Anything resembling a decision belongs in a service, where it can be tested
 * without an HTTP request.
 */
@Controller
public class HomeController {

    /** How many cards the "Just listed" row shows. */
    private static final int RECENT_CARDS = 6;

    /** Placeholder until we have a real figure to compute. */
    private static final int BROKERAGE_SAVED_INR = 4_800_000;

    private final ListingCatalog catalog;

    public HomeController(ListingCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/")
    public String landing(Model model) {
        // Model is the one-way channel from controller to template: a template
        // can only read what is placed here. It cannot reach a repository, so it
        // cannot fetch private data on its own.
        model.addAttribute("cards", catalog.recentlyListed(RECENT_CARDS));
        model.addAttribute("liveCount", catalog.liveCount());
        model.addAttribute("areaCount", 22);
        model.addAttribute("brokerageSaved", Rupees.compact(BROKERAGE_SAVED_INR));
        return "home/landing";
    }
}
