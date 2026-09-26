package com.househunting.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The search page as a browser sees it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class SearchPageTest {

    private static final Pattern CARD_ID =
            Pattern.compile("href=\"/flats/([0-9a-f-]{36})\"");

    @Autowired
    private MockMvc mockMvc;

    /** Fetches a page and returns its HTML, asserting a 200 on the way. */
    private String html(String url) throws Exception {
        return mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---- Rendering --------------------------------------------------------

    @Test
    @DisplayName("the search page renders a full page of cards")
    void rendersResults() throws Exception {
        String html = html("/flats");

        assertThat(countCards(html)).isEqualTo(SearchCriteria.DEFAULT_PAGE_SIZE);
        assertThat(html).contains("flats");
        assertThat(html).contains("Approximate locations only");
    }

    @Test
    @DisplayName("the area panel carries every area, marked so it can be filtered")
    void areaPanelCarriesSublocalities() throws Exception {
        String html = html("/flats");

        // The whole list ships with the page and area-filter.js hides the
        // sublocalities until they are searched for. If they were not here,
        // typing "diamond" would find nothing however good the script was.
        assertThat(html).contains("data-name=\"Chembur\"");
        assertThat(html).contains("data-name=\"Diamond Garden\"");

        // The flag the script filters on. A sublocality whose data-sub was
        // wrong would sit in the default list, and eighty-four localities
        // would become two hundred entries.
        assertThat(html).contains("data-parent=\"Chembur\"");
        assertThat(html).containsPattern("data-name=\"Diamond Garden\",?\\s*[^>]*data-sub=\"true\"");
    }

    @Test
    @DisplayName("Show results appears at both ends of the filter panel")
    void showResultsAppearsTwice() throws Exception {
        String html = html("/flats");

        // The panel is taller than a screen once the area list is open, so
        // whichever end you finish at, the button you need is the one off
        // screen. Counting them is crude but it is the thing that regresses:
        // somebody tidies up "the duplicate button" and the complaint returns.
        assertThat(html.split("Show results", -1).length - 1).isEqualTo(2);
    }

    @Test
    @DisplayName("the heading's number and its noun always agree")
    void headingPluralisationAgrees() throws Exception {
        // Asserting "no 1 flats" against a hardcoded area passes trivially the
        // day the seed puts two listings there — green forever while never
        // rendering the branch it exists to check. And the seed cannot be
        // asked for an area with exactly one listing, because it gives every
        // area four.
        //
        // So this asserts the RULE instead, across several searches: whatever
        // number the heading shows, the word after it must match. That is true
        // for every case, cannot pass vacuously, and catches the singular the
        // moment any of these searches returns one.
        for (String url : List.of(
                "/flats",
                "/flats?area=Chembur",
                "/flats?rentMax=9000",
                "/flats?rentMin=1&rentMax=2")) {

            String heading = headingOf(html(url));
            assertThat(heading).as(url).isNotNull();

            long count = Long.parseLong(heading.replaceAll("\\D+", ""));
            String noun = heading.contains("flats") ? "flats" : "flat";

            assertThat(noun).as("%s showed %d", url, count)
                    .isEqualTo(count == 1 ? "flat" : "flats");
        }
    }

    /** The "340 flats" line, whitespace squashed. */
    private static String headingOf(String html) {
        Matcher m = Pattern.compile(
                "<h1 class=\"search__title\">(.*?)</h1>", Pattern.DOTALL).matcher(html);
        if (!m.find()) {
            return null;
        }
        return m.group(1).replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    @Test
    @DisplayName("filters are reflected in the page, including the active count")
    void filtersAreReflected() throws Exception {
        String html = html("/flats?rentMin=20000&rentMax=30000&furnishing=SEMI_FURNISHED");

        assertThat(html).contains("₹20,000 – ₹30,000");
        // Two groups narrowed: the rent range counts once, furnishing once.
        assertThat(html).containsPattern("filters__count\"?>\\s*2");
        // Checkbox state survives the round trip, so the panel matches the URL.
        assertThat(html).containsPattern("value=\"SEMI_FURNISHED\"[^>]*checked");
    }

    // ---- The three empty states -------------------------------------------

    @Test
    @DisplayName("a radius with nothing in it shows the widened banner")
    void widenedBanner() throws Exception {
        String html = html("/flats?lat=19.0544&lng=72.8404&radius=50");

        assertThat(html).contains("No exact match, so we widened the radius");
        assertThat(html).contains("km");
        assertThat(countCards(html)).isGreaterThan(0);
    }

    @Test
    @DisplayName("an impossible filter shows nearby alternatives rather than nothing")
    void relaxedBanner() throws Exception {
        String html = html("/flats?lat=19.0544&lng=72.8404&radius=2000&rentMin=9000000");

        assertThat(html).contains("Nearby alternatives");
        assertThat(countCards(html)).isGreaterThan(0);
    }

    // ---- Pagination -------------------------------------------------------

    @Test
    @DisplayName("Load more advances the cursor and returns different listings")
    void loadMoreAdvances() throws Exception {
        String page1 = html("/flats?sort=RENT_LOW_TO_HIGH");

        String cursor = extractCursor(page1);
        assertThat(cursor).isNotNull();

        String page2 = html("/flats?sort=RENT_LOW_TO_HIGH&cursor=" + cursor);

        var ids1 = ids(page1);
        var ids2 = ids(page2);

        assertThat(ids1).hasSize(20);
        assertThat(ids2).hasSize(20);
        // Overlap would mean the seek was off by one; a gap would mean rows
        // were skipped and never shown to anybody.
        assertThat(ids1).doesNotContainAnyElementsOf(ids2);
    }

    @Test
    @DisplayName("changing the sort drops the cursor, since it belongs to the old order")
    void sortFormOmitsCursor() throws Exception {
        String html = html("/flats?sort=NEWEST&rentMin=20000");

        // The sort form carries the filters forward but not the cursor: a
        // position in one ordering is meaningless in another, and reusing it
        // would silently skip or repeat listings.
        String sortForm = html.substring(html.indexOf("search__sort"),
                html.indexOf("</form>", html.indexOf("search__sort")));
        assertThat(sortForm).doesNotContain("cursor");
        assertThat(sortForm).contains("rentMin");
    }

    // ---- Robustness -------------------------------------------------------

    @ParameterizedTest
    @DisplayName("malformed query parameters render page one, never an error")
    @ValueSource(strings = {
            "?rentMin=abc",
            "?rentMax=",
            "?moveInBy=not-a-date",
            "?config=BANANA",
            "?room=%27%3B+DROP+TABLE+listings%3B+--",
            "?sort=%27%3BDROP",
            "?cursor=nonsense",
            "?cursor=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "?lat=x&lng=y&radius=z",
            "?area=&area=&area=",
            "?gender=NOPE"
    })
    void malformedParametersDoNotBreakThePage(String query) throws Exception {
        // A search URL is shared, truncated and edited for months. Anything
        // unparseable falls back to "not filtered" rather than to a 500.
        mockMvc.perform(get("/flats" + query))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an oversized page size is clamped")
    void pageSizeIsClamped() throws Exception {
        // maxResults is not bound from the URL at all, but the clamp in
        // SearchCriteria is the backstop if it ever is.
        String html = html("/flats");
        assertThat(countCards(html)).isLessThanOrEqualTo(60);
    }

    // ---- Autocomplete -----------------------------------------------------

    @Test
    @DisplayName("area autocomplete tolerates a misspelling")
    void autocompleteIsFuzzy() throws Exception {
        MvcResult result = mockMvc.perform(get("/flats/areas").param("q", "andhri"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        // pg_trgm compares three-character sequences, so a missing letter still
        // matches. A LIKE query would return nothing for this input.
        assertThat(json).contains("Andheri");
    }

    @Test
    @DisplayName("a prefix ranks above a merely similar name")
    void autocompletePrefersPrefixes() throws Exception {
        String json = mockMvc.perform(get("/flats/areas").param("q", "ban"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Someone typing "ban" means Bandra, not whatever is most
        // trigram-similar to those three letters. Assert on the FIRST result's
        // name rather than on byte offsets, which say nothing about ranking.
        Matcher firstName = Pattern.compile("\"name\":\"([^\"]+)\"").matcher(json);
        assertThat(firstName.find()).isTrue();
        assertThat(firstName.group(1)).startsWith("Bandra");
    }

    @Test
    @DisplayName("an empty autocomplete query returns nothing rather than everything")
    void autocompleteEmptyQuery() throws Exception {
        String json = mockMvc.perform(get("/flats/areas").param("q", ""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json).isEqualTo("[]");
    }

    // ---- Privacy ----------------------------------------------------------

    @Test
    @DisplayName("the search page renders no address, society name or exact coordinate")
    void searchPageLeaksNothingPrivate() throws Exception {
        String html = html("/flats?lat=19.0596&lng=72.8295&radius=3000");

        // DevDataSeeder names every society "<Area> Residency" and every
        // address "Flat N0M, ...". Neither should appear on a public page.
        assertThat(html).doesNotContain("Residency");
        assertThat(html).doesNotContain("Flat 1");
        assertThat(html).doesNotContain("+9190000");
    }

    // ---- Helpers ----------------------------------------------------------

    private static int countCards(String html) {
        return ids(html).size();
    }

    private static java.util.List<String> ids(String html) {
        var found = new java.util.ArrayList<String>();
        Matcher matcher = CARD_ID.matcher(html);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static String extractCursor(String html) {
        Matcher matcher = Pattern.compile("cursor=([A-Za-z0-9_-]+)").matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }
}
