package com.nexttenant.search;

import com.nexttenant.listing.dto.ListingCardDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a search, widening it rather than returning nothing.
 *
 * <p>An empty result is almost always a failure of the search, not of the
 * inventory: someone drew too small a circle, or ticked one filter too many.
 * The design responds by widening and saying so, which this implements as an
 * actual ladder of queries.
 */
@Service
public class ListingSearchService {

    private static final Logger log = LoggerFactory.getLogger(ListingSearchService.class);

    /**
     * Each rung doubles, and the climb stops at 10km.
     *
     * <p>Beyond that "nearby" stops being true: in Mumbai traffic, 15km can be
     * an hour, and showing a Thane flat to someone searching Colaba is not
     * helpful, it is noise. A search that returns nothing is a better answer
     * than one that returns the wrong thing confidently.
     *
     * <p><b>These are multipliers of the ORIGINAL radius</b>, which is the bug
     * that used to live here. The ladder was {2, 4, 8}, so a 50m search could
     * only ever reach 400m — nowhere near the 10km ceiling the comment above
     * promised. Past 400m it stopped widening and fell through to dropping
     * filters instead, which keeps the location but throws away every other
     * constraint: "nothing within 50m, so here is an unfurnished shared room
     * in Thane". Widening distance first and only then relaxing filters is the
     * whole design, and it was being skipped.
     *
     * <p>It passed its test for two years' worth of commits because the dev
     * seed happened to be dense enough that 400m around Bandra always caught
     * something. Reducing the seed from 14 listings per area to 4 is what
     * exposed it — a reminder that a test whose fixture is "whatever the seeder
     * produced" is testing the seeder as much as the code.
     */
    private static final int MAX_RADIUS_METRES = 10_000;

    /**
     * The radii a search climbs through, doubling until the ceiling.
     *
     * <p>This was a fixed list of eight multipliers, {2, 4, 8, ... 256}, and
     * that carried the same bug as the {2, 4, 8} it replaced, only further
     * out: the ladder's REACH depended on where it started. A 1&nbsp;km search
     * climbed to the 10&nbsp;km ceiling the comment above promises; a 50&nbsp;m
     * search stopped at 12.8&nbsp;km-worth of multiplier but a 1&nbsp;m search
     * gave up at 256&nbsp;m and fell through to dropping filters — keeping the
     * place and throwing away every other constraint, which is the outcome the
     * whole design exists to avoid.
     *
     * <p>Generated rather than listed, so the ceiling is a property of the
     * ladder instead of something the list has to be long enough to reach.
     * Doubling keeps the number of queries logarithmic: 1&nbsp;m to
     * 10&nbsp;km is fourteen rungs, and a realistic 500&nbsp;m start is five.
     *
     * <p>Package-private and pure so it can be tested as arithmetic. The old
     * test for this reached through the seeded database and therefore tested
     * the seeder as much as the code — it needed some randomly placed listing
     * to land within 256&nbsp;m of a fixed point, which nothing guaranteed.
     */
    static List<Integer> wideningLadder(int fromMetres) {
        List<Integer> rungs = new java.util.ArrayList<>();
        if (fromMetres >= MAX_RADIUS_METRES) {
            return rungs;
        }
        long radius = Math.max(1, fromMetres);
        while (radius < MAX_RADIUS_METRES) {
            radius *= 2;
            rungs.add((int) Math.min(radius, MAX_RADIUS_METRES));
        }
        return rungs;
    }

    /**
     * How far "nearby areas" reaches, centre to centre.
     *
     * <p>Three kilometres is roughly one locality over in Mumbai — Andheri
     * West to Jogeshwari West, Chembur to Govandi. Far enough to be worth
     * ticking, close enough that the results are still a plausible commute
     * from the same station.
     */
    private static final int NEARBY_METRES = 3000;

    /**
     * Widens the area list before anything is queried.
     *
     * <p>Two expansions, and the ORDER matters. Nearby runs first, over the
     * areas actually ticked, then children are added for the whole resulting
     * set. The other way round would ask "what is near Diamond Garden" for
     * every sublocality of every ticked locality — the same answer computed
     * thirteen times, because a sublocality is measured from its parent's
     * centre anyway.
     *
     * <p>Done once here rather than inside the fallback ladder below, which
     * reuses these criteria several times over.
     */
    private SearchCriteria criteria(SearchCriteria requested) {
        if (requested.areas().isEmpty()) {
            // "Nearby" on its own is not a filter. With nothing ticked there
            // is nothing to be near, and the checkbox is inert rather than
            // quietly meaning "everywhere".
            return requested;
        }

        List<String> names = requested.areas();
        if (requested.includeNearbyAreas()) {
            names = areas.expandToNearby(names, NEARBY_METRES);
        }
        return requested.withAreas(areas.expandWithChildren(names));
    }

    private final ListingSearchRepository repository;
    private final com.nexttenant.area.AreaRepository areas;

    public ListingSearchService(ListingSearchRepository repository,
                                com.nexttenant.area.AreaRepository areas) {
        this.repository = repository;
        this.areas = areas;
    }

    @Transactional(readOnly = true)
    public SearchResults search(SearchCriteria requested) {
        // Asking for a locality means asking for everything inside it. A
        // listing in Diamond Garden stores "Diamond Garden", so without this a
        // search for Chembur would miss it — and "the whole locality" would
        // return fewer rooms than one street inside it.
        //
        // Done once, here, before any of the fallbacks below reuse the
        // criteria. Doing it inside the ladder would re-expand on every rung.
        SearchCriteria criteria = criteria(requested);

        // 1. As asked.
        List<SearchHit> hits = repository.search(criteria);
        if (!hits.isEmpty()) {
            return page(hits, criteria, SearchOutcome.EXACT, null);
        }

        // Widening only makes sense when a place was specified. Without one
        // there is no radius to widen, and dropping filters is the only move.
        if (criteria.hasLocation() && criteria.cursor() == null) {
            for (int widened : wideningLadder(criteria.radiusMetres())) {
                SearchCriteria wider = criteria.withRadius(widened);
                hits = repository.search(wider);
                if (!hits.isEmpty()) {
                    log.debug("Search widened {}m -> {}m, {} hits",
                            criteria.radiusMetres(), widened, hits.size());
                    return page(hits, wider, SearchOutcome.WIDENED, widened);
                }
            }
        }

        // 3. "Nothing at all, even wider" — keep the place, drop the narrowing.
        if (criteria.hasAnyFilter() && criteria.cursor() == null) {
            SearchCriteria relaxed = criteria.hasLocation()
                    ? criteria.locationOnly().withRadius(MAX_RADIUS_METRES)
                    : SearchCriteria.empty();

            hits = repository.search(relaxed);
            if (!hits.isEmpty()) {
                return page(hits, relaxed, SearchOutcome.RELAXED,
                        relaxed.hasLocation() ? relaxed.radiusMetres() : null);
            }
        }

        return SearchResults.empty();
    }

    /**
     * Trims the extra row and builds the cursor.
     *
     * <p>The query asked for one more row than the page size. If it came back,
     * another page exists — which is why there is no COUNT here. Counting a
     * filtered set on every page costs a full scan to answer a question the
     * extra row already answers.
     */
    private SearchResults page(List<SearchHit> hits, SearchCriteria criteria,
                               SearchOutcome outcome, Integer widenedTo) {
        boolean hasMore = hits.size() > criteria.maxResults();
        List<SearchHit> visible = hasMore ? hits.subList(0, criteria.maxResults()) : hits;

        List<ListingCardDto> cards = new ArrayList<>(visible.size());
        for (SearchHit hit : visible) {
            cards.add(hit.card());
        }

        String nextCursor = null;
        if (hasMore && !visible.isEmpty()) {
            SearchHit last = visible.getLast();
            nextCursor = new SearchCursor(last.sortValue(), last.id()).encode();
        }

        long total = repository.count(criteria);

        return new SearchResults(cards, total, outcome, widenedTo, nextCursor);
    }
}
