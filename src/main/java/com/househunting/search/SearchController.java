package com.househunting.search;

import com.househunting.area.Area;
import com.househunting.area.AreaRepository;
import com.househunting.auth.AuthenticatedUser;
import com.househunting.common.Rupees;
import com.househunting.listing.Configuration;
import com.househunting.listing.FlatmateGender;
import com.househunting.listing.Furnishing;
import com.househunting.listing.RoomType;
import com.househunting.shortlist.ShortlistService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * The search page.
 *
 * <p>All state is in the URL. Filters kept in the session look simpler and
 * break in ways people notice immediately: the back button does nothing, a
 * search cannot be shared, opening a listing in a new tab loses the filters,
 * and a refresh shows something different. It also makes "Save this search" a
 * matter of storing a query string.
 */
@Controller
public class SearchController {

    private final ListingSearchService search;
    private final AreaRepository areas;
    private final ShortlistService shortlists;

    public SearchController(ListingSearchService search, AreaRepository areas,
                            ShortlistService shortlists) {
        this.search = search;
        this.areas = areas;
        this.shortlists = shortlists;
    }

    @GetMapping("/flats")
    public String results(@ModelAttribute SearchForm form,
                          @AuthenticationPrincipal AuthenticatedUser user,
                          Model model) {
        SearchCriteria criteria = form.toCriteria();
        SearchResults results = search.search(criteria);

        model.addAttribute("savedIds",
                shortlists.savedAmong(user == null ? null : user.id(), results.cards()));
        model.addAttribute("form", form);
        model.addAttribute("results", results);
        model.addAttribute("criteria", criteria);
        model.addAttribute("baseQuery", form.toQueryStringWithoutCursor());

        // The filter panel's options. Supplied from the enums so the panel
        // cannot drift out of step with what the database will accept.
        // Everything, localities and sublocalities alike. The panel renders them
        // all and area-filter.js hides the sublocalities until they are searched
        // for — see the comment in search/index.html. findTopLevel() is still
        // right for anywhere that wants a short list.
        model.addAttribute("allAreas", areas.findAllActive());
        model.addAttribute("configurations", Configuration.values());
        model.addAttribute("roomTypes", RoomType.values());
        model.addAttribute("furnishings", Furnishing.values());
        model.addAttribute("genders", FlatmateGender.values());
        model.addAttribute("amenities", AMENITIES);
        model.addAttribute("sortOptions", SortOrder.values());

        model.addAttribute("rentRangeLabel", rentRangeLabel(criteria));
        model.addAttribute("filterCount", criteria.activeFilterCount());

        return "search/index";
    }

    /**
     * Area autocomplete.
     *
     * <p>{@code @ResponseBody} returns JSON rather than a view — the one place
     * this application behaves like an API, because a type-ahead cannot reload
     * the page. Everything else stays server-rendered.
     */
    @GetMapping("/flats/areas")
    @ResponseBody
    public List<AreaSuggestion> suggestAreas(@RequestParam(value = "q", required = false) String query) {
        return areas.search(query, 8).stream()
                .map(a -> new AreaSuggestion(a.name(), a.subtitle(),
                        a.latitude(), a.longitude(), a.radiusMetres()))
                .toList();
    }

    /**
     * One autocomplete row.
     *
     * <p>Carries the centroid and radius so picking an area turns straight into
     * a radius search without a second request.
     */
    public record AreaSuggestion(String name, String subtitle,
                                 double lat, double lng, int radius) {
    }

    /** "₹15,000 – ₹35,000" for the results header. */
    private String rentRangeLabel(SearchCriteria criteria) {
        if (criteria.rentMin() == null && criteria.rentMax() == null) {
            return null;
        }
        if (criteria.rentMin() != null && criteria.rentMax() != null) {
            return Rupees.format(criteria.rentMin()) + " – " + Rupees.format(criteria.rentMax());
        }
        return criteria.rentMin() != null
                ? "from " + Rupees.format(criteria.rentMin())
                : "up to " + Rupees.format(criteria.rentMax());
    }

    /**
     * The "Must have" vocabulary.
     *
     * <p>Held here rather than read from the data: the panel should offer a
     * stable, curated list, not whatever strings happen to exist in the
     * listings table. When this outgrows a constant it becomes a table.
     */
    private static final List<Amenity> AMENITIES = List.of(
            new Amenity("LIFT", "Lift"),
            new Amenity("PARKING", "Parking"),
            new Amenity("WIFI", "Wi-Fi"),
            new Amenity("WASHING_MACHINE", "Washing machine"),
            new Amenity("FRIDGE", "Fridge"),
            new Amenity("AC", "Air conditioning"),
            new Amenity("GEYSER", "Geyser"),
            new Amenity("POWER_BACKUP", "Power backup"),
            new Amenity("SECURITY", "Security"),
            new Amenity("GYM", "Gym"));

    public record Amenity(String code, String label) {
    }
}
