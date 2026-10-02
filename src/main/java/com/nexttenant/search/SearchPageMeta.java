package com.nexttenant.search;

import com.nexttenant.seo.Budgets;
import com.nexttenant.seo.PageMeta;
import com.nexttenant.seo.Slugs;
import org.springframework.stereotype.Component;

/**
 * What the filter UI tells search engines about itself.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>{@code /flats} with filters is one page with an unbounded number of
 * addresses. {@code ?area=Powai}, {@code ?area=Powai&sort=rent},
 * {@code ?area=Powai&amenity=LIFT&cursor=…} — all different URLs, mostly the
 * same rooms. Left alone, one page's standing is divided among its own
 * permutations and none of them ranks.
 *
 * <h2>Two different answers, not one</h2>
 *
 * <p>It is tempting to noindex every filtered view and be done. That is wrong
 * for the ones that correspond to a real page: a canonical CONSOLIDATES — it
 * passes the link value of every variant to the clean URL — while noindex
 * throws it away, and combining the two sends Google contradictory
 * instructions about a page it cannot act on both ways. So:
 *
 * <ul>
 *   <li>A filter set that matches a landing page — one area, optionally with
 *       one of the {@link Budgets} ceilings — points its canonical at that
 *       page and is <b>not</b> noindexed. The variant is a duplicate, and
 *       saying so is the whole job of a canonical.</li>
 *   <li>Anything else — three amenities and a gender filter — is noindexed,
 *       because there is no clean page for it to consolidate into and no
 *       searcher it would serve.</li>
 * </ul>
 *
 * <p>Pagination is a third case. A cursor page is self-canonical, never
 * canonical to page one: pointing page two at page one tells Google the
 * listings on page two are duplicates of different listings, so it stops
 * crawling them and they are never indexed at all.
 */
@Component
public class SearchPageMeta {

    /** The unfiltered page, which is a real page and should rank. */
    private static final String DESCRIPTION =
            "Rooms in shared flats across Mumbai, taken over directly from the person "
                    + "moving out. Filter by area, rent and move-in date. No brokerage.";

    public PageMeta forResults(SearchForm form, SearchCriteria criteria, SearchResults results) {
        boolean paginated = form.cursor() != null && !form.cursor().isBlank();

        // Page two of anything. Self-canonical and noindex: the listings on it
        // are reachable from the sitemap and from the area pages, so there is
        // nothing to lose by keeping the paginated view out of the index, and a
        // canonical to page one would actively stop them being crawled.
        if (paginated) {
            return PageMeta.page(title(criteria, results))
                    .describedAs(DESCRIPTION)
                    .canonical("/flats?" + form.toQueryStringWithoutCursor())
                    .noindex()
                    .build();
        }

        if (criteria.activeFilterCount() == 0) {
            return PageMeta.page("Flats and rooms to rent in Mumbai without broker")
                    .describedAs(DESCRIPTION)
                    .canonical("/flats")
                    .build();
        }

        String landing = landingPathFor(criteria);
        if (landing != null) {
            // A duplicate of a page that exists. Canonical to it, and let the
            // standing flow there rather than evaporating.
            return PageMeta.page(title(criteria, results))
                    .describedAs(DESCRIPTION)
                    .canonical(landing)
                    .build();
        }

        return PageMeta.page(title(criteria, results))
                .describedAs(DESCRIPTION)
                .noindex()
                .build();
    }

    /**
     * The clean URL this filter set is a duplicate of, or null.
     *
     * <p>Deliberately strict. One area, or one area and one allowed budget, and
     * nothing else — because a canonical is an assertion that two URLs are the
     * same page, and claiming it for a filter set that shows different rooms is
     * how the wrong page ends up in the index.
     *
     * <p>Note what is NOT checked: whether the slug exists. A canonical to a
     * 404 is worse than none, but the areas in {@code criteria} came from the
     * filter panel, which is built from the same table the landing pages
     * resolve against. The one way to get an area name that has no row is to
     * type one into the query string by hand, and that URL is not one anybody
     * links to.
     */
    private String landingPathFor(SearchCriteria criteria) {
        if (criteria.areas().size() != 1 || criteria.station() != null
                || criteria.latitude() != null
                || criteria.moveInBy() != null
                || !criteria.configurations().isEmpty()
                || !criteria.roomTypes().isEmpty()
                || !criteria.furnishings().isEmpty()
                || criteria.flatmateGender() != null
                || !criteria.mustHaveAmenities().isEmpty()
                || criteria.rentMin() != null) {
            return null;
        }

        String slug = Slugs.of(criteria.areas().getFirst());
        if (slug.isEmpty()) {
            return null;
        }

        if (criteria.rentMax() == null) {
            return "/flats-in-" + slug;
        }
        // A budget that has a page of its own. Any other ceiling — ₹27,350,
        // typed by hand or dragged on a slider — has no clean equivalent, so
        // the page falls through to noindex rather than canonicalising to a
        // budget it does not actually filter by.
        return Budgets.isAllowed(criteria.rentMax())
                ? "/flats-in-" + slug + "-under-" + criteria.rentMax()
                : null;
    }

    private String title(SearchCriteria criteria, SearchResults results) {
        if (criteria.areas().size() == 1) {
            return "Flats in " + criteria.areas().getFirst() + " — "
                    + results.totalCount() + (results.totalCount() == 1 ? " room" : " rooms");
        }
        return "Rooms to rent in Mumbai";
    }
}
