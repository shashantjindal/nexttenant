package com.nexttenant.home;

import com.nexttenant.area.AreaRepository;
import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.common.Rupees;
import com.nexttenant.listing.ListingCatalog;
import com.nexttenant.listing.dto.ListingCardDto;
import com.nexttenant.seo.JsonLd;
import com.nexttenant.seo.PageMeta;
import com.nexttenant.shortlist.ShortlistService;
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

    private final ListingCatalog catalog;
    private final AreaRepository areas;
    private final ShortlistService shortlists;
    private final JsonLd jsonLd;

    public HomeController(ListingCatalog catalog, AreaRepository areas,
                          ShortlistService shortlists, JsonLd jsonLd) {
        this.catalog = catalog;
        this.areas = areas;
        this.shortlists = shortlists;
        this.jsonLd = jsonLd;
    }

    @GetMapping("/")
    public String landing(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        List<ListingCardDto> cards = catalog.recentlyListed(RECENT_CARDS);

        // Model is the one-way channel from controller to template: a template
        // can only read what is placed here. It cannot reach a repository, so it
        // cannot fetch private data on its own.
        model.addAttribute("cards", cards);

        // The stat strip that consumed these is commented out in the template
        // until there is enough supply for the numbers to help rather than
        // hurt — see the note there. Both attributes stay, and both queries
        // with them: they are one trivial count each on one page, and removing
        // them would mean reconstructing this when the strip comes back, by
        // which time nobody remembers where areaCount came from.
        //
        // areaCount was the literal 22 once, which stopped being true the
        // moment V10 landed. A number on a marketing line is a claim, and a
        // claim that has to be remembered when unrelated data changes is a
        // claim that will be wrong.
        model.addAttribute("liveCount", catalog.liveCount());
        model.addAttribute("areaCount", areas.countActive());

        // "Brokerage saved" is no longer on the landing page. It was a number
        // computed about transactions we did not witness, shown to somebody
        // still deciding whether the site has any flats at all - which on a
        // site this new reads as invented. brokerageSavedInr() stays for the
        // admin side, where the audience already knows what it counts.

        // Which of these the viewer has already hearted. One query for the row,
        // not one per card.
        model.addAttribute("savedIds",
                shortlists.savedAmong(user == null ? null : user.id(), cards));

        // The one page whose description has to work for somebody who has never
        // heard of the site and is searching for something else entirely. It
        // says what the site IS rather than what it offers, because "find your
        // perfect home" is what every other result on that page already says.
        model.addAttribute("pageMeta", PageMeta.page(
                        "Shared flats in Mumbai, direct from the person leaving")
                .describedAs("Take over a room in a shared flat in Mumbai from the person "
                        + "moving out. No brokers, no owners, no PGs — and no brokerage.")
                .canonical("/")
                .structuredData(jsonLd.page(List.of(new JsonLd.Crumb("Home", "/"))))
                .build());

        return "home/landing";
    }
}
