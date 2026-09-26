package com.househunting.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter panel's reading of a URL.
 *
 * <p>No Spring context: every method here is a function of the query string,
 * and a test that needs no context starts in milliseconds and gets run.
 */
class SearchFormTest {

    /** Builds a form the way Spring's binder would, with everything else empty. */
    private static SearchForm form(String moveInBy, String flexible) {
        return new SearchForm(List.of(), null, null, null, null, moveInBy, flexible,
                List.of(), List.of(), List.of(), null, List.of(),
                null, null, null, null, null);
    }

    /** As above, but for the area side of the panel. */
    private static SearchForm areaForm(List<String> areas, String nearby) {
        return new SearchForm(areas, nearby, null, null, null, null, null,
                List.of(), List.of(), List.of(), null, List.of(),
                null, null, null, null, null);
    }

    // ---- Nearby areas -----------------------------------------------------

    @Test
    @DisplayName("the nearby tick reaches the criteria")
    void nearbyReachesTheCriteria() {
        assertThat(areaForm(List.of("Chembur"), "1").toCriteria().includeNearbyAreas()).isTrue();
        assertThat(areaForm(List.of("Chembur"), null).toCriteria().includeNearbyAreas()).isFalse();
        // An unticked checkbox submits nothing; a hand-edited URL can leave it
        // blank. Both mean the same thing.
        assertThat(areaForm(List.of("Chembur"), "").wantsNearbyAreas()).isFalse();
    }

    @Test
    @DisplayName("the nearby tick survives a sort change")
    void nearbySurvivesTheQueryStringRoundTrip() {
        // The sort control and "Load more" rebuild the query string from the
        // form. A filter that dropped out of that rebuild would show up as
        // "my results changed when I sorted them".
        String query = areaForm(List.of("Chembur"), "1").toQueryStringWithoutCursor();

        assertThat(query).contains("area=Chembur");
        assertThat(query).contains("nearby=1");
    }

    // ---- The move-in date -------------------------------------------------

    @Test
    @DisplayName("without the tick, the date is a hard cutoff")
    void strictMoveIn() {
        SearchForm f = form("2026-11-01", null);

        assertThat(f.wantsLaterMoveIns()).isFalse();
        assertThat(f.effectiveMoveInBy()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("ticking it pushes the cutoff out by the grace period")
    void flexibleMoveInWidensTheWindow() {
        SearchForm f = form("2026-11-01", "1");

        assertThat(f.wantsLaterMoveIns()).isTrue();
        // The checkbox does not add a new KIND of condition — the SQL still
        // reads "available_from <= :moveInBy". It changes which date that is,
        // which is why the whole feature lives in this class and the search
        // layer did not have to learn about it.
        assertThat(f.effectiveMoveInBy())
                .isEqualTo(LocalDate.of(2026, 11, 1).plusDays(SearchForm.FLEXIBLE_GRACE_DAYS));
        assertThat(f.toCriteria().moveInBy()).isEqualTo(f.effectiveMoveInBy());
    }

    @Test
    @DisplayName("the tick alone filters nothing")
    void flexibleWithoutADateIsHarmless() {
        // An old bookmark, or somebody who ticked the box and then cleared the
        // date. "Six weeks after nothing" is not a date, and the filter should
        // simply not apply.
        SearchForm f = form(null, "1");

        assertThat(f.effectiveMoveInBy()).isNull();
        assertThat(f.toCriteria().moveInBy()).isNull();
    }

    @Test
    @DisplayName("an unparseable date is ignored rather than fatal")
    void rubbishDateIsIgnored() {
        // A search URL is shared, truncated and edited. ?moveInBy=soon should
        // show results, not a 500.
        assertThat(form("soon", "1").effectiveMoveInBy()).isNull();
        assertThat(form("31-11-2026", null).effectiveMoveInBy()).isNull();
    }

    @Test
    @DisplayName("the tick survives a sort change")
    void flexibleSurvivesTheQueryStringRoundTrip() {
        // The sort control and "Load more" rebuild the query string from the
        // form. A filter that silently dropped out of that rebuild would
        // reappear as "my results changed when I sorted them".
        String query = form("2026-11-01", "1").toQueryStringWithoutCursor();

        assertThat(query).contains("moveInBy=2026-11-01");
        assertThat(query).contains("flexibleMoveIn=1");
    }

    @Test
    @DisplayName("an absent checkbox is the same as a blank one")
    void absentAndBlankAgree() {
        // An unticked checkbox submits nothing at all, so the parameter is
        // missing rather than empty — but a hand-edited URL can leave it blank.
        assertThat(form("2026-11-01", null).wantsLaterMoveIns()).isFalse();
        assertThat(form("2026-11-01", "").wantsLaterMoveIns()).isFalse();
        assertThat(form("2026-11-01", "  ").wantsLaterMoveIns()).isFalse();
    }
}
