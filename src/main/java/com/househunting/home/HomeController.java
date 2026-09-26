package com.househunting.home;

import com.househunting.area.AreaRepository;
import com.househunting.auth.AuthenticatedUser;
import com.househunting.common.Rupees;
import com.househunting.listing.ListingCatalog;
import com.househunting.listing.dto.ListingCardDto;
import com.househunting.shortlist.ShortlistService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

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
    private final AreaRepository areas;
    private final ShortlistService shortlists;

    public HomeController(ListingCatalog catalog, AreaRepository areas, ShortlistService shortlists) {
        this.catalog = catalog;
        this.areas = areas;
        this.shortlists = shortlists;
    }

    @GetMapping("/")
    public String landing(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        List<ListingCardDto> cards = catalog.recentlyListed(RECENT_CARDS);

        // Model is the one-way channel from controller to template: a template
        // can only read what is placed here. It cannot reach a repository, so it
        // cannot fetch private data on its own.
        model.addAttribute("cards", cards);
        model.addAttribute("liveCount", catalog.liveCount());

        // Was the literal 22, which stopped being true the moment V10 landed.
        // A number on a marketing line is still a claim, and a claim that has
        // to be remembered when unrelated data changes is a claim that will be
        // wrong. Counting it costs one trivial query on one page.
        model.addAttribute("areaCount", areas.countActive());

        model.addAttribute("brokerageSaved", Rupees.compact(BROKERAGE_SAVED_INR));

        // Which of these the viewer has already hearted. One query for the row,
        // not one per card.
        model.addAttribute("savedIds",
                shortlists.savedAmong(user == null ? null : user.id(), cards));
        return "home/landing";
    }
}
