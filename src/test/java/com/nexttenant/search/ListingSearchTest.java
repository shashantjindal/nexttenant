package com.nexttenant.search;

import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.Furnishing;
import com.nexttenant.listing.RoomType;
import com.nexttenant.listing.dto.ListingCardDto;
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
    @Autowired private com.nexttenant.area.AreaRepository areas;

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
    @DisplayName("an area filter restricts to that locality and the sublocalities inside it")
    void areaFilter() {
        List<String> asked = List.of("Bandra West", "Khar West");
        SearchResults results = search.search(criteria().areas(asked).build());

        // Since V13 an area filter means "this locality AND everything inside
        // it" — asking for Bandra West and being shown nothing in Pali Hill
        // would be the bug, not the other way round. So the allowed set is
        // computed the same way the search computes it, rather than hardcoded.
        Set<String> allowed = new HashSet<>(areas.expandWithChildren(asked));

        assertThat(results.cards()).isNotEmpty();
        assertThat(results.cards()).allSatisfy(c -> assertThat(c.area()).isIn(allowed));

        // And the expansion is real: this would pass vacuously if the filter
        // were simply being ignored.
        assertThat(allowed).contains("Pali Hill");
        assertThat(allowed).doesNotContain("Powai", "Colaba");
    }

    @Test
    @DisplayName("filters combine, narrowing rather than widening")
    void filtersCombine() {
        SearchResults broad = search.search(criteria().areas(List.of("Powai")).build());
        SearchResults narrow = search.search(criteria()
                .areas(List.of("Powai"))
                .roomTypes(List.of(RoomType.PRIVATE_ROOM))
                .furnishings(List.of(Furnishing.FULLY_FURNISHED))
                .build());

        // The claim only holds while the search is still answering the question
        // it was asked. A narrow filter that matches nothing triggers the
        // RELAXED fallback, which deliberately DROPS filters and can therefore
        // return more rows than the broader search — that is the fallback
        // working, not the filters failing to combine.
        //
        // The original version of this assertion did not check the outcome and
        // passed only because the seed happened to leave enough matches. It
        // started failing the moment sublocalities split Powai's listings
        // across several area names, which is exactly the kind of latent
        // assertion that fails a fortnight later and gets deleted rather than
        // understood.
        assertThat(broad.outcome()).isEqualTo(SearchOutcome.EXACT);

        if (narrow.outcome() == SearchOutcome.EXACT) {
            assertThat(narrow.totalCount()).isLessThanOrEqualTo(broad.totalCount());
        } else {
            // Nothing matched all three filters, so the ladder dropped some.
            // The properties worth pinning then: the visitor gets rooms rather
            // than a dead end, and the page can tell them the filters were
            // relaxed — a larger count that claimed to be EXACT would be the
            // real bug here.
            assertThat(narrow.outcome()).isEqualTo(SearchOutcome.RELAXED);
            assertThat(narrow.cards()).isNotEmpty();
        }
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
    @DisplayName("the ladder reaches the ceiling from any starting radius")
    void theLadderAlwaysReachesTheCeiling() {
        // Asserted as arithmetic, not through the database. The previous
        // version of this test needed some randomly seeded listing to land
        // within 256m of a fixed point, so it was testing the seeder as much
        // as the code — and it failed whenever the dice came up differently.
        //
        // What it was trying to prove is a property of the ladder, and the
        // ladder is a pure function, so this proves it directly.
        for (int start : new int[]{1, 50, 500, 1_000, 5_000}) {
            assertThat(ListingSearchService.wideningLadder(start))
                    .describedAs("ladder from %dm", start)
                    .isNotEmpty()
                    .last().isEqualTo(10_000);
        }
    }

    @Test
    @DisplayName("a tiny radius still climbs past the old 8x ceiling")
    void theLadderClimbsPastTheOldCeiling() {
        // The bug this guards: the ladder used to be a fixed list of eight
        // multipliers, so its REACH depended on where it started. From 1m it
        // stopped at 256m and fell through to dropping filters — keeping the
        // place and discarding every other constraint, which is exactly the
        // outcome widening exists to avoid.
        var rungs = ListingSearchService.wideningLadder(1);

        assertThat(rungs).contains(8);           // the old ceiling
        assertThat(rungs.getLast()).isEqualTo(10_000);
        assertThat(rungs).isSortedAccordingTo(Integer::compareTo);
    }

    @Test
    @DisplayName("a radius already at the ceiling has nowhere left to widen")
    void nothingToWidenAtTheCeiling() {
        // Without this the loop would offer a rung equal to the radius it was
        // already searching, costing a second identical query to learn what
        // the first one just said.
        assertThat(ListingSearchService.wideningLadder(10_000)).isEmpty();
        assertThat(ListingSearchService.wideningLadder(20_000)).isEmpty();
    }

    @Test
    @DisplayName("a search that finds nothing nearby widens rather than dropping filters")
    void wideningComesBeforeRelaxing() {
        // The behavioural half, kept deliberately coarse: it asserts the
        // ORDER of the two fallbacks and nothing about distances, because
        // distances here depend on where the seeder put things.
        SearchResults results = search.search(criteria()
                .location(LAT, LNG, 1).build());

        assertThat(results.outcome())
                .describedAs("widening distance comes before discarding filters")
                .isNotEqualTo(SearchOutcome.RELAXED);
        assertThat(results.cards()).isNotEmpty();
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
