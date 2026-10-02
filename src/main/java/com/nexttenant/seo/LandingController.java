package com.nexttenant.seo;

import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.listing.RoomType;
import com.nexttenant.listing.dto.ListingCardDto;
import com.nexttenant.search.ListingSearchService;
import com.nexttenant.search.SearchCriteria;
import com.nexttenant.search.SearchResults;
import com.nexttenant.shortlist.ShortlistService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * The pages people arrive on from a search engine.
 *
 * <h2>Why these are paths and not query strings</h2>
 *
 * <p>{@code /flats?area=Powai} and {@code /flats-in-powai} can serve identical
 * content, and only one of them is a page. A query string says "this is the
 * search tool, with some arguments"; a path says "this is the page about Powai".
 * Links, shares and search results accumulate against the second and scatter
 * across the permutations of the first — which is why the filter UI keeps its
 * query strings and points its canonical here.
 *
 * <h2>Every route resolves against the database or 404s</h2>
 *
 * <p>There is no route here that accepts an arbitrary word. The area and
 * station segments must be rows in {@code areas}; the budget must be one of
 * {@link Budgets#ALLOWED}. A site that answers 200 for invented URLs generates
 * unlimited near-identical pages, and Google treats that as a property of the
 * whole domain rather than of the pages.
 *
 * <h2>Why the room-type variant exists</h2>
 *
 * <p>{@code /shared-flats-in-powai} is not a synonym for {@code /flats-in-powai}
 * — it filters to shared rooms, so the two pages show different listings and
 * each earns its own canonical. Were they the same content at two addresses,
 * one would have to canonicalise to the other and the second would be pointless.
 */
@Controller
public class LandingController {

    /**
     * Listings per landing page.
     *
     * <p>Larger than the search page's, and deliberately not paginated. These
     * pages exist to be landed on and read; a second page of results would be
     * another URL with nearly the same content and no additional searcher
     * behind it. Somebody who wants to go deeper has the filter UI, which is
     * where paging belongs.
     */
    private static final int PAGE_SIZE = 24;

    private final LandingPageRepository landings;
    private final ListingSearchService search;
    private final ShortlistService shortlists;
    private final JsonLd jsonLd;

    public LandingController(LandingPageRepository landings, ListingSearchService search,
                             ShortlistService shortlists, JsonLd jsonLd) {
        this.landings = landings;
        this.search = search;
        this.shortlists = shortlists;
        this.jsonLd = jsonLd;
    }

    // ---- /flats-in-{area} ---------------------------------------------------

    /**
     * The area page, and its budget variant.
     *
     * <p><b>One mapping, parsed here, rather than two patterns.</b> The obvious
     * shape is a second mapping for {@code /flats-in-{slug}-under-{budget}},
     * and it does not work: both patterns match
     * {@code /flats-in-powai-under-25000} — the first with a slug of
     * "powai-under-25000" — and which one Spring chooses depends on its pattern
     * comparator rather than on anything written here. It chose the wrong one,
     * and the symptom was a 404 on a URL that is in the sitemap.
     *
     * <p>Splitting the suffix in code makes the precedence explicit and
     * testable. The separator cannot collide with a real slug: no Mumbai
     * locality is called "something under something".
     */
    @GetMapping("/flats-in-{path}")
    public String area(@PathVariable String path,
                       @AuthenticationPrincipal AuthenticatedUser viewer,
                       Model model) {
        int split = path.lastIndexOf(UNDER);
        if (split < 0) {
            return render(area(path), null, null, viewer, model);
        }

        Integer budget = parseBudget(path.substring(split + UNDER.length()));
        if (budget == null) {
            // Not a number, or not one of the budgets with a page. 404 rather
            // than falling back to the area page or redirecting to the nearest
            // allowed figure: either of those would make every invented number
            // a working URL, which is the unbounded space Budgets exists to
            // prevent.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        return render(area(path.substring(0, split)), budget, null, viewer, model);
    }

    /** The separator in {@code /flats-in-powai-under-25000}. */
    private static final String UNDER = "-under-";

    private static Integer parseBudget(String raw) {
        try {
            int budget = Integer.parseInt(raw);
            return Budgets.isAllowed(budget) ? budget : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- /shared-flats-in-{area} -------------------------------------------

    @GetMapping("/shared-flats-in-{slug}")
    public String sharedFlatsInArea(@PathVariable String slug,
                                    @AuthenticationPrincipal AuthenticatedUser viewer,
                                    Model model) {
        AreaLanding area = area(slug);
        return render(area, null, RoomType.SHARED_ROOM, viewer, model);
    }

    // ---- /flats-near-{station}-station --------------------------------------

    /**
     * Everything within walking distance of one station.
     *
     * <p>Searched by area names rather than by the {@code station} filter,
     * because a listing records the station nearest to IT, which is not always
     * the station of the area it sits in — a room in Powai names Kanjurmarg.
     * Expanding the station to its areas and searching those matches what the
     * page claims to be: the rooms in the places this station serves.
     */
    @GetMapping("/flats-near-{slug}-station")
    public String station(@PathVariable String slug,
                          @AuthenticationPrincipal AuthenticatedUser viewer,
                          Model model) {
        List<AreaLanding> areas = landings.findAreasByStationSlug(slug);
        if (areas.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        String stationName = areas.getFirst().nearestStation();
        String path = "/flats-near-" + slug + "-station";

        SearchResults results = search.search(new SearchCriteria.Builder()
                .areas(areas.stream().map(AreaLanding::name).toList())
                .maxResults(PAGE_SIZE)
                .build());

        String heading = "Flats near " + stationName + " station";
        String title = results.totalCount() > 0
                ? "Flats near " + stationName + " station without broker — "
                        + results.totalCount() + " " + rooms(results.totalCount())
                : "Flats near " + stationName + " station without broker";

        model.addAttribute("heading", heading);
        model.addAttribute("lede", "Rooms in "
                + humanJoin(areas.stream().map(AreaLanding::name).toList())
                + ", taken over directly from the person leaving.");
        model.addAttribute("intro", null);
        model.addAttribute("relatedTitle", "Areas near " + stationName);
        model.addAttribute("related", areas.stream()
                .map(a -> new Link(a.name(), a.path()))
                .toList());
        model.addAttribute("budgets", List.of());

        addResults(model, results, viewer);

        model.addAttribute("pageMeta", PageMeta.page(title)
                .describedAs("Shared flats and private rooms near " + stationName
                        + " station, direct from the person moving out. No brokerage, "
                        + "no owners, no PGs.")
                .canonical(path)
                // A station page with nothing on it is a page about an absence.
                // It still renders — somebody who followed a link should see
                // why it is empty — but it does not belong in the index until
                // it has something to show.
                .noindexIf(results.isEmpty())
                .structuredData(jsonLd.itemList(heading, path,
                        results.cards().stream().map(ListingCardDto::path).toList(),
                        List.of(new JsonLd.Crumb("Home", "/"),
                                new JsonLd.Crumb("Flats", "/flats"),
                                new JsonLd.Crumb(stationName + " station", path))))
                .build());

        return "seo/landing";
    }

    // ---- Shared rendering ---------------------------------------------------

    private String render(AreaLanding area, Integer budget, RoomType roomType,
                          AuthenticatedUser viewer, Model model) {

        SearchCriteria.Builder criteria = new SearchCriteria.Builder()
                .areas(List.of(area.name()))
                // A sublocality on its own is often a handful of rooms. Widening
                // to the surrounding locality makes the page useful rather than
                // empty, and the heading says which place it is about — so the
                // page is not claiming the extra rooms are in Diamond Garden.
                .includeNearbyAreas(area.isSublocality())
                .maxResults(PAGE_SIZE);

        if (budget != null) {
            criteria.rentMax(budget);
        }
        if (roomType != null) {
            criteria.roomTypes(List.of(roomType));
        }

        SearchResults results = onlyWhatWasAskedFor(search.search(criteria.build()));

        String path = budget != null ? area.pathUnder(budget)
                : roomType != null ? area.sharedPath()
                : area.path();

        String heading = heading(area, budget, roomType);
        model.addAttribute("heading", heading);
        model.addAttribute("lede", lede(area, budget, roomType));
        model.addAttribute("intro", area.hasIntro() ? Markdown.toHtml(area.intro()) : null);
        model.addAttribute("introHeading", "About " + area.name());

        // Internal links out of the page, which are most of what makes a set of
        // landing pages work: they give a crawler a route between them that
        // does not run through a paginated search, and they give a reader the
        // next question they were going to ask.
        model.addAttribute("budgets", budget == null && roomType == null
                ? Budgets.ALLOWED.stream()
                        .map(b -> new Link("Under " + Budgets.format(b), area.pathUnder(b)))
                        .toList()
                : List.of());
        model.addAttribute("relatedTitle", "Nearby");
        model.addAttribute("related", relatedLinks(area, budget, roomType));

        addResults(model, results, viewer);

        model.addAttribute("pageMeta", PageMeta.page(title(area, budget, roomType, results.totalCount()))
                .describedAs(description(area, budget, roomType))
                .canonical(path)
                .noindexIf(results.isEmpty() && !area.hasIntro())
                .structuredData(jsonLd.itemList(heading, path,
                        results.cards().stream().map(ListingCardDto::path).toList(),
                        crumbs(area, budget, roomType, path)))
                .build());

        return "seo/landing";
    }

    /**
     * Discards results the search returned by dropping the filters.
     *
     * <p>{@link ListingSearchService} falls back when a search finds nothing:
     * it widens the radius, and failing that keeps the place and drops the
     * narrowing. That is right for the filter UI, where somebody is looking and
     * something beats nothing. It is wrong here.
     *
     * <p>A landing page's title, heading and canonical URL all assert a
     * constraint — "in Powai", "under ₹25,000". Showing ₹28,000 rooms under a
     * heading that says ₹25,000 is false on the page and worse in a search
     * result, because the assertion is what somebody clicked. An empty page
     * that says so is honest, and the controller marks it noindex, so nobody
     * arrives on it from Google in the first place.
     *
     * <p>Caught here rather than by asking the search not to relax, because the
     * fallback is a property of that service that the rest of the site wants.
     */
    private static SearchResults onlyWhatWasAskedFor(SearchResults results) {
        return results.wasBroadened() ? SearchResults.empty() : results;
    }

    private void addResults(Model model, SearchResults results, AuthenticatedUser viewer) {
        model.addAttribute("results", results);
        model.addAttribute("savedIds",
                shortlists.savedAmong(viewer == null ? null : viewer.id(), results.cards()));
    }

    /** One internal link, for the template. */
    public record Link(String label, String path) {
    }

    // ---- Copy ---------------------------------------------------------------
    //
    // The title and description patterns live here rather than in the template
    // because they are assembled from live data and are the two strings a
    // searcher judges the result by. In a template they would be string
    // concatenation inside th:text, unreadable and untestable; here they are
    // methods a test can assert on.

    /** "Flats in Powai without broker — 34 rooms available | Next Tenant" */
    private String title(AreaLanding area, Integer budget, RoomType roomType, long count) {
        StringBuilder title = new StringBuilder();
        title.append(roomType == RoomType.SHARED_ROOM ? "Shared flats in " : "Flats in ")
                .append(area.name());
        if (budget != null) {
            title.append(" under ").append(Budgets.format(budget));
        }
        title.append(" without broker");
        if (count > 0) {
            title.append(" — ").append(count).append(' ').append(rooms(count)).append(" available");
        }
        return title.toString();
    }

    /**
     * The sentence under the title in a search result.
     *
     * <p>An editor's own sentence wins outright when there is one, and only for
     * the plain area page: it was written about Powai, not about rooms in Powai
     * under ₹25,000, and reusing it on the variants would put the same
     * description on ten pages — which is the duplicate-description warning in
     * Search Console, and a wasted line in nine results.
     */
    private String description(AreaLanding area, Integer budget, RoomType roomType) {
        if (budget == null && roomType == null
                && area.metaDescription() != null && !area.metaDescription().isBlank()) {
            return area.metaDescription();
        }

        StringBuilder text = new StringBuilder();
        text.append(roomType == RoomType.SHARED_ROOM
                ? "Shared rooms in " : "Private rooms and shared flats in ");
        text.append(area.name());
        if (budget != null) {
            text.append(" under ").append(Budgets.format(budget)).append(" a month");
        }
        text.append(", taken over directly from the person moving out. ");
        text.append("Zero brokerage, verified flatmates, no owners and no PGs.");
        return text.toString();
    }

    private String heading(AreaLanding area, Integer budget, RoomType roomType) {
        StringBuilder heading = new StringBuilder();
        heading.append(roomType == RoomType.SHARED_ROOM ? "Shared flats in " : "Flats in ")
                .append(area.name());
        if (budget != null) {
            heading.append(" under ").append(Budgets.format(budget));
        }
        return heading.toString();
    }

    private String lede(AreaLanding area, Integer budget, RoomType roomType) {
        if (budget != null) {
            return "Rooms in " + area.name() + " at " + Budgets.format(budget)
                    + " a month or less, direct from the person leaving.";
        }
        if (roomType == RoomType.SHARED_ROOM) {
            return "Sharing a room in " + area.name() + " — the cheapest way to live close in.";
        }
        return "Take over a room in " + area.name() + " from the person moving out. "
                + "No brokerage, and you meet the flatmates before you commit.";
    }

    private List<JsonLd.Crumb> crumbs(AreaLanding area, Integer budget, RoomType roomType,
                                      String path) {
        List<JsonLd.Crumb> crumbs = new ArrayList<>(4);
        crumbs.add(new JsonLd.Crumb("Home", "/"));
        crumbs.add(new JsonLd.Crumb("Flats", "/flats"));
        if (budget != null || roomType != null) {
            // The plain area page is the parent of both variants, so the trail
            // leads a reader back to the broader version rather than stopping
            // at the search page.
            crumbs.add(new JsonLd.Crumb(area.name(), area.path()));
        }
        crumbs.add(new JsonLd.Crumb(heading(area, budget, roomType), path));
        return crumbs;
    }

    /**
     * Where a reader goes next.
     *
     * <p>From a budget or shared-rooms page, back up to the whole area. From an
     * area page, out to its station and to its parent locality. These are the
     * links that stop each landing page being a dead end — for a reader and for
     * a crawler, which reaches most of these pages no other way.
     */
    private List<Link> relatedLinks(AreaLanding area, Integer budget, RoomType roomType) {
        List<Link> links = new ArrayList<>(3);
        if (budget != null || roomType != null) {
            links.add(new Link("All flats in " + area.name(), area.path()));
        }
        if (budget == null && roomType == null) {
            links.add(new Link("Shared rooms in " + area.name(), area.sharedPath()));
        }
        links.add(new Link("Near " + area.nearestStation() + " station",
                "/flats-near-" + area.stationSlug() + "-station"));
        return links;
    }

    private AreaLanding area(String slug) {
        return landings.findAreaBySlug(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String rooms(long count) {
        return count == 1 ? "room" : "rooms";
    }

    /** "Andheri East and Andheri West", "Powai", "A, B and C". */
    private static String humanJoin(List<String> names) {
        if (names.size() == 1) {
            return names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1))
                + " and " + names.getLast();
    }
}
