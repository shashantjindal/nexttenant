package com.househunting.search;

import com.househunting.listing.dto.ListingCardDto;
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
     * Each rung doubles roughly, and stops at 10km.
     *
     * <p>Beyond that "nearby" stops being true: in Mumbai traffic, 15km can be
     * an hour, and showing a Thane flat to someone searching Colaba is not
     * helpful, it is noise. A search that returns nothing is a better answer
     * than one that returns the wrong thing confidently.
     */
    private static final int[] WIDENING_LADDER = {2, 4, 8};
    private static final int MAX_RADIUS_METRES = 10_000;

    private final ListingSearchRepository repository;

    public ListingSearchService(ListingSearchRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public SearchResults search(SearchCriteria criteria) {
        // 1. As asked.
        List<SearchHit> hits = repository.search(criteria);
        if (!hits.isEmpty()) {
            return page(hits, criteria, SearchOutcome.EXACT, null);
        }

        // Widening only makes sense when a place was specified. Without one
        // there is no radius to widen, and dropping filters is the only move.
        if (criteria.hasLocation() && criteria.cursor() == null) {
            for (int multiplier : WIDENING_LADDER) {
                int widened = Math.min(criteria.radiusMetres() * multiplier, MAX_RADIUS_METRES);
                if (widened <= criteria.radiusMetres()) {
                    break;   // already at the ceiling
                }

                SearchCriteria wider = criteria.withRadius(widened);
                hits = repository.search(wider);
                if (!hits.isEmpty()) {
                    log.debug("Search widened {}m -> {}m, {} hits",
                            criteria.radiusMetres(), widened, hits.size());
                    return page(hits, wider, SearchOutcome.WIDENED, widened);
                }

                if (widened >= MAX_RADIUS_METRES) {
                    break;
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
