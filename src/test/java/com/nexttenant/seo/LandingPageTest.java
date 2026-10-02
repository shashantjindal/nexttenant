package com.nexttenant.seo;

import com.nexttenant.geo.GeoPoints;
import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.Furnishing;
import com.nexttenant.listing.HandoverType;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingPrivate;
import com.nexttenant.listing.ListingPrivateRepository;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.listing.RoomType;
import com.nexttenant.user.AgeBracket;
import com.nexttenant.user.Gender;
import com.nexttenant.user.User;
import com.nexttenant.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The clean URLs people arrive on from a search engine.
 *
 * <p>Two things are being protected here. That the pages work signed out — a
 * login redirect is what Googlebot would index — and that the URL space stays
 * finite, because a site answering 200 for invented addresses generates
 * unlimited near-identical pages and is treated accordingly.
 */
@SpringBootTest(properties = "nexttenant.site.base-url=https://nexttenant.test")
@AutoConfigureMockMvc
@Transactional
class LandingPageTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;
    @Autowired private LandingPageRepository landings;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        User leaver = users.save(new User("Priya", Gender.FEMALE, AgeBracket.AGE_23_27));
        publish(leaver, "Powai", "Kanjurmarg", 28_000);
    }

    // ---- The lookup table ---------------------------------------------------

    @Test
    @DisplayName("slugs are generated from names, with no application involvement")
    void slugsAreGenerated() {
        // The column is GENERATED ALWAYS, so an area inserted by anything at all
        // — a migration, a fixture, a hand-typed INSERT during an incident —
        // gets a slug. This is the test that would have caught the earlier
        // version, where a NOT NULL column broke every fixture that inserted
        // an area.
        jdbc.sql("""
                        INSERT INTO areas (id, name, nearest_station, centroid, radius_metres)
                        VALUES (9101, 'Test Locality West', 'Test Road',
                                ST_MakePoint(72.9, 19.1)::geography, 1500)
                        """)
                .update();

        assertThat(landings.findAreaBySlug("test-locality-west")).isPresent();
    }

    @Test
    @DisplayName("the SQL and the Java agree on what a slug is")
    void slugRulesMatch() {
        // Two implementations of one rule: the generated column in V20 and
        // seo/Slugs. They are used at opposite ends — one stores the URL, the
        // other builds links to it — so a disagreement is a link to a 404.
        var rows = jdbc.sql("SELECT name, slug FROM areas WHERE active ORDER BY id LIMIT 50")
                .query((rs, n) -> new String[]{rs.getString("name"), rs.getString("slug")})
                .list();

        assertThat(rows).isNotEmpty();
        for (String[] row : rows) {
            assertThat(Slugs.of(row[0]))
                    .as("Java slug for '%s'", row[0])
                    .isEqualTo(row[1]);
        }
    }

    // ---- The pages ----------------------------------------------------------

    @Test
    @DisplayName("an area page works signed out, and names itself canonical")
    void areaPageIsPublicAndCanonical() throws Exception {
        String html = body("/flats-in-powai");

        assertThat(html).contains("<link rel=\"canonical\" href=\"https://nexttenant.test/flats-in-powai\"");
        assertThat(html).contains("Flats in Powai");
        // Indexable: it has a room on it.
        assertThat(html).contains("content=\"index, follow");
    }

    @Test
    @DisplayName("the title and description are unique to the page")
    void metaIsSpecific() throws Exception {
        String html = body("/flats-in-powai");

        assertThat(html).contains("Flats in Powai without broker");
        assertThat(html).contains("<meta name=\"description\" content=\"");
        assertThat(html).contains("rooms available");
    }

    @Test
    @DisplayName("an invented area is a 404, not a thin page")
    void unknownAreaIs404() throws Exception {
        mockMvc.perform(get("/flats-in-atlantis")).andExpect(status().isNotFound());
        mockMvc.perform(get("/shared-flats-in-atlantis")).andExpect(status().isNotFound());
        mockMvc.perform(get("/flats-near-atlantis-station")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("only the listed budgets have a page")
    void budgetsAreAnAllowList() throws Exception {
        // The whole point: /flats-in-powai-under-25001 must not exist, or the
        // URL space is unbounded over a bounded set of rooms.
        mockMvc.perform(get("/flats-in-powai-under-25000")).andExpect(status().isOk());
        mockMvc.perform(get("/flats-in-powai-under-25001")).andExpect(status().isNotFound());
        mockMvc.perform(get("/flats-in-powai-under-1")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a budget page canonicalises to itself, not to the area")
    void budgetPagesAreTheirOwnCanonical() throws Exception {
        // It shows a different set of rooms, so it is a different page. A
        // canonical to the area page would tell Google to drop it.
        assertThat(body("/flats-in-powai-under-30000"))
                .contains("rel=\"canonical\" href=\"https://nexttenant.test/flats-in-powai-under-30000\"");
    }

    @Test
    @DisplayName("the shared-rooms page is a page of its own")
    void sharedFlatsPageExists() throws Exception {
        assertThat(body("/shared-flats-in-powai"))
                .contains("rel=\"canonical\" href=\"https://nexttenant.test/shared-flats-in-powai\"");
    }

    @Test
    @DisplayName("a station page covers the areas it serves")
    void stationPageWorks() throws Exception {
        String html = body("/flats-near-kanjurmarg-station");

        assertThat(html).contains("Flats near Kanjurmarg station");
        assertThat(html).contains("/flats-in-powai");
    }

    @Test
    @DisplayName("a page with nothing on it is kept out of the index")
    void emptyPagesAreNoindex() throws Exception {
        // It still renders — somebody followed a link here — but an empty page
        // in the index is a searcher landing on nothing.
        String html = body("/flats-in-powai-under-10000");

        assertThat(html).contains("content=\"noindex, follow\"");
        assertThat(html).contains("No rooms here right now.");
    }

    @Test
    @DisplayName("every landing page links onward, so none is a dead end")
    void pagesLinkOnward() throws Exception {
        // A crawler reaches the budget variants through these links and
        // nowhere else — they are deliberately not in the sitemap.
        String html = body("/flats-in-powai");

        assertThat(html).contains("/flats-in-powai-under-25000");
        assertThat(html).contains("/shared-flats-in-powai");
        assertThat(html).contains("/flats-near-kanjurmarg-station");
    }

    // ---- Structured data ----------------------------------------------------

    @Test
    @DisplayName("the page carries an ItemList and a breadcrumb trail")
    void structuredDataIsPresent() throws Exception {
        String html = body("/flats-in-powai");

        assertThat(html).contains("application/ld+json");
        assertThat(html).contains("\"@type\":\"ItemList\"");
        assertThat(html).contains("\"@type\":\"BreadcrumbList\"");
        assertThat(html).contains("\"@type\":\"Organization\"");
    }

    @Test
    @DisplayName("nothing in the JSON-LD can close the script element")
    void structuredDataCannotEscapeItsBlock() throws Exception {
        // The block is written unescaped. An unescaped "</" anywhere in it ends
        // the script early and turns the rest into HTML.
        String html = body("/flats-in-powai");
        int start = html.indexOf("application/ld+json");
        int end = html.indexOf("</script>", start);

        assertThat(html.substring(start, end)).doesNotContain("</");
    }

    // ---- Prose --------------------------------------------------------------

    @Test
    @DisplayName("editor prose renders above the grid, as HTML")
    void introRenders() throws Exception {
        landings.saveIntro(areaId("powai"), """
                ## Living in Powai

                The lake end is quieter than **Hiranandani**, and the rent shows it.
                Buses to BKC run all day, which the map does not make obvious at all,
                and the walk to Kanjurmarg takes longer than it looks on paper here.
                """, "Rooms in Powai, direct from the person leaving.");

        String html = body("/flats-in-powai");

        assertThat(html).contains("<h2>Living in Powai</h2>");
        assertThat(html).contains("<strong>Hiranandani</strong>");
        assertThat(html).contains("Rooms in Powai, direct from the person leaving.");
    }

    @Test
    @DisplayName("an area with prose stays indexable even with no rooms")
    void proseAloneJustifiesIndexing() throws Exception {
        // The page still answers the question somebody searched for. An empty
        // grid under real writing is a page worth having; an empty grid alone
        // is not.
        landings.saveIntro(areaId("chembur"), """
                ## Chembur

                Chembur is further out than people expect and better connected than
                they expect too, because the Eastern Freeway puts town within half an
                hour outside the peak, which nothing about the map suggests at all.
                """, null);

        assertThat(body("/flats-in-chembur")).contains("content=\"index, follow");
    }

    // ---- Fixtures -----------------------------------------------------------

    private short areaId(String slug) {
        return landings.findAreaBySlug(slug).orElseThrow().id();
    }

    private String body(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void publish(User owner, String area, String station, int rent) {
        Listing l = new Listing(owner.getId());
        l.setLocationDetails(area, station, (short) 8, (short) 7, true);
        l.setFlatDetails(Configuration.BHK_2, RoomType.PRIVATE_ROOM,
                HandoverType.ROOM_ONLY, (short) 2, Furnishing.SEMI_FURNISHED);
        l.setMoney(rent, rent * 2, null, false, 0, 0);
        l.setAvailableFrom(LocalDate.now().plusDays(30));
        listings.saveAndFlush(l);

        ListingPrivate priv = new ListingPrivate(l.getId());
        priv.placeAt(GeoPoints.of(19.1176, 72.9060));
        listingPrivates.saveAndFlush(priv);

        l.publish(java.time.Instant.now(), 14);
        listings.saveAndFlush(l);
    }
}
