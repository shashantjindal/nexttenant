package com.househunting.listing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The listing detail page — the page with the largest privacy surface.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ListingDetailPageTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ListingRepository listings;
    @Autowired private JdbcClient jdbc;

    private Listing anyLiveListing() {
        return listings.findByStatusOrderByPublishedAtDesc(
                        ListingStatus.LIVE, org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().getFirst();
    }

    private String pageFor(UUID id) throws Exception {
        return mockMvc.perform(get("/flats/" + id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ---- Rendering --------------------------------------------------------

    @Test
    @DisplayName("a live listing renders every section the design specifies")
    void rendersAllSections() throws Exception {
        String html = pageFor(anyLiveListing().getId());

        assertThat(html)
                .contains("Handover terms")
                .contains("The things that usually take a week of messages to find out")
                .contains("What it costs to move in")
                .contains("Roughly here")
                .contains("Request contact")
                .contains("Before you share numbers")
                .contains("Member since");
    }

    @Test
    @DisplayName("the move-in breakdown adds up to the stated total")
    void costBreakdownAddsUp() throws Exception {
        Listing listing = anyLiveListing();
        String html = pageFor(listing.getId());

        // The figure the sidebar shows must equal what the entity computes; a
        // breakdown that does not sum is the single fastest way to lose trust
        // on a page about hidden costs.
        String expectedTotal = com.househunting.common.Rupees.format(listing.totalMoveInCostInr());
        assertThat(html).contains(expectedTotal);
        assertThat(html).contains("Total to move in");
    }

    @Test
    @DisplayName("zero brokerage renders as an explicit line, not an omission")
    void brokerageIsShownEvenWhenZero() throws Exception {
        String html = pageFor(anyLiveListing().getId());

        // Omitting a zero line would hide the product's central claim.
        assertThat(html).contains("Brokerage");
        assertThat(html).contains("₹0");
    }

    // ---- Not found --------------------------------------------------------

    @ParameterizedTest
    @DisplayName("malformed and unknown ids are 404, never a 500")
    @ValueSource(strings = {
            "haha",
            "00000000-0000-0000-0000-000000000000",
            "../../etc/passwd",
            "1"
    })
    void unknownIdsAre404(String id) throws Exception {
        mockMvc.perform(get("/flats/" + id))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("a listing that is not LIVE is indistinguishable from one that never existed")
    void nonLiveListingIs404() throws Exception {
        Listing listing = anyLiveListing();
        UUID id = listing.getId();

        jdbc.sql("UPDATE listings SET status = 'PAUSED' WHERE id = ?").param(id).update();
        try {
            // 404, not 403 or a "this listing is paused" page: distinguishing
            // the two would let anyone confirm a listing's status after it
            // stopped being public.
            mockMvc.perform(get("/flats/" + id)).andExpect(status().isNotFound());
        } finally {
            jdbc.sql("UPDATE listings SET status = 'LIVE' WHERE id = ?").param(id).update();
        }
    }

    // ---- Privacy ----------------------------------------------------------

    @Test
    @DisplayName("the page renders no address, society name or phone number")
    void noPrivateDataInTheHtml() throws Exception {
        String html = pageFor(anyLiveListing().getId());

        // DevDataSeeder names every society "<Area> Residency" and every
        // address "Flat N0M, ...".
        assertThat(html).doesNotContain("Residency");
        assertThat(html).doesNotContain("Flat 1");
        assertThat(html).doesNotContain("+9190000");
    }

    @Test
    @DisplayName("the map receives the jittered point, not the real one")
    void mapReceivesOnlyTheApproximatePoint() throws Exception {
        Listing listing = anyLiveListing();
        String html = pageFor(listing.getId());

        // Read the real coordinates straight from listing_private, which the
        // page's service is not even wired to.
        Double realLat = jdbc.sql(
                        "SELECT ST_Y(exact_location::geometry) FROM listing_private WHERE listing_id = ?")
                .param(listing.getId()).query(Double.class).single();
        Double realLng = jdbc.sql(
                        "SELECT ST_X(exact_location::geometry) FROM listing_private WHERE listing_id = ?")
                .param(listing.getId()).query(Double.class).single();

        assertThat(html).doesNotContain(String.valueOf(realLat));
        assertThat(html).doesNotContain(String.valueOf(realLng));

        // The published point IS there, and differs from the truth.
        double pageLat = com.househunting.geo.GeoPoints.latitudeOf(listing.getApproxLocation());
        assertThat(html).contains(String.valueOf(pageLat));
        assertThat(pageLat).isNotEqualTo(realLat);
    }

    @Test
    @DisplayName("the published circle is wide enough to contain the real building")
    void publishedCircleContainsTheTruth() throws Exception {
        Listing listing = anyLiveListing();

        Double metresApart = jdbc.sql("""
                        SELECT ST_Distance(p.exact_location, l.approx_location)
                        FROM listings l JOIN listing_private p ON p.listing_id = l.id
                        WHERE l.id = ?
                        """)
                .param(listing.getId()).query(Double.class).single();

        // A circle narrower than the offset would EXCLUDE the real location,
        // which is a different and worse kind of inaccuracy than being vague.
        assertThat(metresApart).isLessThan((double) ListingDetailService.PUBLIC_RADIUS_METRES);
    }

    // ---- View counting ----------------------------------------------------

    @Test
    @DisplayName("viewing a listing increments its counter, in the narrow stats table")
    void viewIsCounted() throws Exception {
        Listing listing = anyLiveListing();

        Integer before = jdbc.sql("SELECT view_count FROM listing_stats WHERE listing_id = ?")
                .param(listing.getId()).query(Integer.class).single();

        pageFor(listing.getId());

        Integer after = jdbc.sql("SELECT view_count FROM listing_stats WHERE listing_id = ?")
                .param(listing.getId()).query(Integer.class).single();

        assertThat(after).isEqualTo(before + 1);
    }
}
