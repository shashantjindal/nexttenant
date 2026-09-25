package com.househunting.search;

import com.househunting.listing.Configuration;
import com.househunting.listing.Furnishing;
import com.househunting.listing.RoomType;
import com.househunting.listing.dto.ListingCardDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search behaviour against the seeded dataset.
 *
 * <p>{@code @ActiveProfiles("dev")} so DevDataSeeder guarantees a populated
 * database. The seed uses a fixed Random seed, so these assertions are stable
 * run to run — random fixtures produce tests that fail once a fortnight and get
 * deleted rather than investigated.
 */
@SpringBootTest
@ActiveProfiles("dev")
class ListingSearchTest {

    /** Bandra station. */
    private static final double LAT = 19.0544;
    private static final double LNG = 72.8404;

    @Autowired private ListingSearchService search;
    @Autowired private ListingSearchRepository repository;

    private SearchCriteria.Builder criteria() {
        return new SearchCriteria.Builder();
    }

    // ---- Basic filtering --------------------------------------------------

    @Test
    @DisplayName("an unfiltered search returns a full page and a cursor")
    void unfilteredSearch() {
        SearchResults results = search.search(SearchCriteria.empty());

        assertThat(results.cards()).hasSize(SearchCriteria.DEFAULT_PAGE_SIZE);
        assertThat(results.totalCount()).isGreaterThan(300);
        assertThat(results.outcome()).isEqualTo(SearchOutcome.EXACT);
        assertThat(results.hasNextPage()).isTrue();
    }

    @Test
    @DisplayName("a rent range excludes everything outside it")
    void rentRangeFilter() {
        SearchResults results = search.search(criteria()
                .rentMin(20000).rentMax(25000).build());

        assertThat(results.cards()).isNotEmpty();
        for (ListingCardDto card : results.cards()) {
            int rent = parseRupees(card.rent());
            assertThat(rent).isBetween(20000, 25000);
        }
    }

    @Test
    @DisplayName("a reversed rent range is straightened rather than matching nothing")
    void reversedRentRangeIsCorrected() {
        // min above max would match nothing and look like a broken search
        // rather than a mistyped filter.
        SearchResults results = search.search(criteria()
                .rentMin(25000).rentMax(20000).build());

        assertThat(results.cards()).isNotEmpty();
    }

    @Test
    @DisplayName("an area filter restricts to that area")
    void areaFilter() {
        SearchResults results = search.search(criteria()
                .areas(List.of("Bandra West", "Khar West")).build());

        assertThat(results.cards()).isNotEmpty();
        assertThat(results.cards())
                .allSatisfy(c -> assertThat(c.area()).isIn("Bandra West", "Khar West"));
    }

    @Test
    @DisplayName("filters combine, narrowing rather than widening")
    void filtersCombine() {
        long broad = search.search(criteria().areas(List.of("Powai")).build()).totalCount();
        long narrow = search.search(criteria()
                .areas(List.of("Powai"))
                .roomTypes(List.of(RoomType.PRIVATE_ROOM))
                .furnishings(List.of(Furnishing.FULLY_FURNISHED))
                .build()).totalCount();

        assertThat(narrow).isLessThanOrEqualTo(broad);
    }

    @Test
    @DisplayName("must-have amenities means ALL of them, not any")
    void amenitiesUseContainment() {
        SearchResults results = search.search(criteria()
                .mustHaveAmenities(List.of("LIFT", "PARKING")).build());

        long withBoth = repository.count(criteria()
                .mustHaveAmenities(List.of("LIFT", "PARKING")).build());
        long withLiftOnly = repository.count(criteria()
                .mustHaveAmenities(List.of("LIFT")).build());

        // Requiring two amenities cannot match more listings than requiring
        // one of them — which is exactly what @> guarantees and && would not.
        assertThat(withBoth).isLessThanOrEqualTo(withLiftOnly);
        assertThat(results.cards()).isNotEmpty();
    }

    // ---- Sorting ----------------------------------------------------------

    @Test
    @DisplayName("rent low to high is actually ascending")
    void sortByRentAscending() {
        SearchResults results = search.search(criteria()
                .sort(SortOrder.RENT_LOW_TO_HIGH).build());

        List<Integer> rents = results.cards().stream().map(c -> parseRupees(c.rent())).toList();
        assertThat(rents).isSorted();
    }

    @Test
    @DisplayName("an unknown sort parameter falls back instead of failing")
    void unknownSortFallsBack() {
        // ORDER BY cannot be parameterised, so the column must come from the
        // enum. Anything else — including an injection attempt — resolves to
        // the default.
        assertThat(SortOrder.fromParam("'; DROP TABLE listings; --"))
                .isEqualTo(SortOrder.NEWEST);
        assertThat(SortOrder.fromParam(null)).isEqualTo(SortOrder.NEWEST);
        assertThat(SortOrder.fromParam("rent_low_to_high")).isEqualTo(SortOrder.RENT_LOW_TO_HIGH);
    }

    // ---- Keyset pagination ------------------------------------------------

    @Test
    @DisplayName("paging through the whole set yields no duplicates and no gaps")
    void keysetPaginationIsComplete() {
        long total = repository.count(SearchCriteria.empty());

        Set<UUID> seen = new HashSet<>();
        List<UUID> order = new ArrayList<>();
        SearchCriteria page = SearchCriteria.empty();

        for (int guard = 0; guard < 50; guard++) {
            SearchResults results = search.search(page);
            for (ListingCardDto card : results.cards()) {
                // The assertion that catches an off-by-one in the seek: a row
                // returned twice means the cursor overlapped, a missing row
                // means it skipped.
                assertThat(seen.add(card.id()))
                        .as("listing %s appeared on two pages", card.id())
                        .isTrue();
                order.add(card.id());
            }
            if (!results.hasNextPage()) {
                break;
            }
            page = page.withCursor(SearchCursor.decode(results.nextCursor()).orElseThrow());
        }

        assertThat(seen).hasSize((int) total);
        assertThat(order).hasSize((int) total);
    }

    @Test
    @DisplayName("pagination holds for rent sort too, where ties are common")
    void keysetHandlesTiedSortValues() {
        // Many listings share a rent because it is rounded to 500, so the id
        // tiebreak in the row comparison is doing real work here.
        Set<UUID> seen = new HashSet<>();
        SearchCriteria page = criteria().sort(SortOrder.RENT_LOW_TO_HIGH).build();

        for (int guard = 0; guard < 50; guard++) {
            SearchResults results = search.search(page);
            results.cards().forEach(c -> assertThat(seen.add(c.id())).isTrue());
            if (!results.hasNextPage()) {
                break;
            }
            page = page.withCursor(SearchCursor.decode(results.nextCursor()).orElseThrow());
        }

        assertThat(seen).hasSize((int) repository.count(SearchCriteria.empty()));
    }

    @Test
    @DisplayName("a mangled cursor shows page one rather than a 500")
    void malformedCursorIsIgnored() {
        // Cursors travel in URLs that people share, truncate and edit.
        assertThat(SearchCursor.decode("not-base64!!")).isEmpty();
        assertThat(SearchCursor.decode("")).isEmpty();
        assertThat(SearchCursor.decode(null)).isEmpty();
    }

    @Test
    @DisplayName("a cursor round-trips through its encoding")
    void cursorRoundTrips() {
        UUID id = UUID.randomUUID();
        SearchCursor original = new SearchCursor("2026-09-25 10:00:00+00", id);
        SearchCursor decoded = SearchCursor.decode(original.encode()).orElseThrow();

        assertThat(decoded.id()).isEqualTo(id);
        assertThat(decoded.sortValue()).isEqualTo(original.sortValue());
    }

    // ---- The widening ladder ----------------------------------------------

    @Test
    @DisplayName("a radius with nothing in it is widened, and says so")
    void radiusIsWidened() {
        // 50m around Bandra station contains no listing, but 2km does.
        SearchResults results = search.search(criteria()
                .location(LAT, LNG, 50).build());

        assertThat(results.cards()).isNotEmpty();
        assertThat(results.outcome()).isEqualTo(SearchOutcome.WIDENED);
        assertThat(results.widenedToMetres()).isGreaterThan(50);
        assertThat(results.wasBroadened()).isTrue();
    }

    @Test
    @DisplayName("an impossible filter falls back to dropping filters, not to nothing")
    void filtersAreRelaxed() {
        SearchResults results = search.search(criteria()
                .location(LAT, LNG, 2000)
                .rentMin(9_000_000)        // no flat in Mumbai costs this
                .build());

        assertThat(results.outcome()).isEqualTo(SearchOutcome.RELAXED);
        assertThat(results.cards()).isNotEmpty();
    }

    @Test
    @DisplayName("an exact match is reported as exact, not widened")
    void exactMatchIsNotWidened() {
        SearchResults results = search.search(criteria()
                .location(LAT, LNG, 5000).build());

        assertThat(results.outcome()).isEqualTo(SearchOutcome.EXACT);
        assertThat(results.widenedToMetres()).isNull();
        assertThat(results.wasBroadened()).isFalse();
    }

    // ---- Injection --------------------------------------------------------

    @Test
    @DisplayName("SQL metacharacters in filter values are treated as data")
    void injectionAttemptsAreJustValues() {
        // Bound as a parameter, so this reaches PostgreSQL as a string to
        // compare, never as SQL to parse. It simply matches no area.
        SearchResults results = search.search(criteria()
                .areas(List.of("Bandra'; DROP TABLE listings; --")).build());

        // Falls through to the relaxed branch because nothing matched, which is
        // the correct behaviour for a filter value that matches nothing.
        assertThat(results.outcome()).isIn(SearchOutcome.RELAXED, SearchOutcome.EMPTY);

        // And the table is still there.
        assertThat(repository.count(SearchCriteria.empty())).isGreaterThan(300);
    }

    // ---- Query plans ------------------------------------------------------

    @Test
    @DisplayName("the radius search uses the GiST index now that there is real data")
    void radiusSearchUsesSpatialIndex() {
        // With a dozen rows PostgreSQL ignored this index, because scanning one
        // page beat any index lookup. At 300 rows the plan starts telling the
        // truth — which is why tuning against toy data proves nothing.
        String plan = repository.explain(criteria().location(LAT, LNG, 2000).build());

        assertThat(plan).containsIgnoringCase("listings_approx_location_ix");
    }

    @Test
    @DisplayName("sorting by rent over live listings uses the partial index")
    void rentSortUsesPartialIndex() {
        String plan = repository.explain(criteria().sort(SortOrder.RENT_LOW_TO_HIGH).build());

        // The partial index (WHERE status = 'LIVE') covers exactly the rows a
        // public search looks at, so it stays small and hot.
        assertThat(plan).containsIgnoringCase("listings_live_rent_ix");
    }

    // ---- Privacy ----------------------------------------------------------

    @Test
    @DisplayName("no search query touches listing_private")
    void searchNeverJoinsPrivateData() {
        String plan = repository.explain(criteria().location(LAT, LNG, 3000).build());

        // Structural: the SELECT does not mention the table, so no filter bug
        // or future column addition can pull an exact address into a result.
        assertThat(plan).doesNotContainIgnoringCase("listing_private");
    }

    // ---- Helpers ----------------------------------------------------------

    private static int parseRupees(String formatted) {
        return Integer.parseInt(formatted.replaceAll("[^0-9]", ""));
    }
}
