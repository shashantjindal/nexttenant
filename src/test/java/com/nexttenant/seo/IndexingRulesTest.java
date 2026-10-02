package com.nexttenant.seo;

import com.nexttenant.geo.GeoPoints;
import com.nexttenant.guide.GuideRepository;
import com.nexttenant.guide.GuideStatus;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which pages are allowed into the index, and what happens to the ones that
 * leave it.
 *
 * <p>Both halves matter and they fail in opposite directions. A page wrongly
 * indexed is a privacy problem or a searcher landing on an error; a page
 * wrongly excluded is traffic that never arrives and nobody notices.
 */
@SpringBootTest(properties = "nexttenant.site.base-url=https://nexttenant.test")
@AutoConfigureMockMvc
@Transactional
class IndexingRulesTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;
    @Autowired private GuideRepository guides;
    @Autowired private JdbcClient jdbc;
    @Autowired private PageMetaAdvice pageMetaAdvice;

    private Listing live;

    @BeforeEach
    void setUp() {
        User leaver = users.save(new User("Priya", Gender.FEMALE, AgeBracket.AGE_23_27));
        live = publish(leaver, "Powai", "Kanjurmarg");
    }

    // ---- The default is not to be indexed -----------------------------------

    @Test
    @DisplayName("a page that says nothing about SEO is noindexed, not indexed by accident")
    void defaultIsNoindex() {
        // The direction this fails in is the whole design. A controller that
        // adds no PageMeta gets this one. A missed opt-in costs traffic; a
        // missed opt-out puts a signed-in screen in the search results, and
        // only one of those is recoverable.
        assertThat(pageMetaAdvice.defaultMeta().noindex()).isTrue();
        assertThat(pageMetaAdvice.defaultMeta().canonicalPath()).isNull();
    }

    @Test
    @DisplayName("noindex never comes with nofollow")
    void linksAreStillFollowed() throws Exception {
        // noindex says "do not list this page"; nofollow says "ignore every
        // link on it". A filtered search page should not be listed, but the
        // listings it links to very much should be found through it — nofollow
        // would wall them off from discovery to solve a problem nobody had.
        String html = body("/flats?area=Powai&amenity=LIFT&furnishing=SEMI_FURNISHED");

        assertThat(html).contains("content=\"noindex, follow\"");
        assertThat(html).doesNotContain("nofollow");
    }

    // ---- Listings -----------------------------------------------------------

    @Test
    @DisplayName("a live listing is indexable and carries Accommodation data")
    void liveListingIsIndexable() throws Exception {
        String html = body(path(live));

        assertThat(html).contains("content=\"index, follow");
        assertThat(html).contains("\"@type\":[\"Accommodation\",\"Product\"]");
        assertThat(html).contains("\"priceCurrency\":\"INR\"");
        assertThat(html).contains("\"@type\":\"BreadcrumbList\"");
    }

    @Test
    @DisplayName("the title carries the room, the flat, the area and the rent")
    void listingTitleIsSpecific() throws Exception {
        assertThat(body(path(live)))
                .contains("Private room in 2 BHK, Powai")
                .contains("/month");
    }

    @Test
    @DisplayName("a paused listing is a 404, not a page that says it is paused")
    void pausedListingIs404() throws Exception {
        live.pause();
        listings.saveAndFlush(live);

        mockMvc.perform(get(path(live))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a long-dead listing is 410, so Google drops it rather than retrying")
    void longExpiredListingIsGone() throws Exception {
        // Terminal, and well past the grace period. 404 would keep the URL in
        // Google's index for weeks while it re-requested a dead page.
        jdbc.sql("""
                        UPDATE listings
                           SET status = 'EXPIRED',
                               expires_at = now() - interval '200 days',
                               updated_at = now() - interval '200 days'
                         WHERE id = :id
                        """)
                .param("id", live.getId())
                .update();

        mockMvc.perform(get(path(live))).andExpect(status().isGone());
    }

    @Test
    @DisplayName("a recently expired listing is still only 404")
    void recentlyExpiredListingIsNotGone() throws Exception {
        // It may well be extended. 410 would throw away an indexed page that is
        // about to be live again, and re-indexing takes far longer than the
        // lapse did.
        jdbc.sql("""
                        UPDATE listings
                           SET status = 'EXPIRED',
                               expires_at = now() - interval '2 days',
                               updated_at = now() - interval '2 days'
                         WHERE id = :id
                        """)
                .param("id", live.getId())
                .update();

        mockMvc.perform(get(path(live))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a URL that never existed is 404, never 410")
    void unknownListingIsNotGone() throws Exception {
        // 410 would be a claim about a page we never published.
        mockMvc.perform(get("/flats/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    // ---- The filter UI ------------------------------------------------------

    @Test
    @DisplayName("the unfiltered search page is a real page")
    void unfilteredSearchIsIndexable() throws Exception {
        String html = body("/flats");

        assertThat(html).contains("rel=\"canonical\" href=\"https://nexttenant.test/flats\"");
        assertThat(html).contains("content=\"index, follow");
    }

    @Test
    @DisplayName("one area filter canonicalises to the clean area page")
    void areaFilterCanonicalisesToLanding() throws Exception {
        // Canonical rather than noindex, deliberately: a canonical passes this
        // variant's link value to the clean page, where noindex throws it away.
        // Combining the two would also be contradictory.
        String html = body("/flats?area=Powai");

        assertThat(html).contains("rel=\"canonical\" href=\"https://nexttenant.test/flats-in-powai\"");
        assertThat(html).doesNotContain("noindex");
    }

    @Test
    @DisplayName("an area and an allowed budget canonicalise to the budget page")
    void budgetFilterCanonicalisesToLanding() throws Exception {
        assertThat(body("/flats?area=Powai&rentMax=25000"))
                .contains("rel=\"canonical\" href=\"https://nexttenant.test/flats-in-powai-under-25000\"");
    }

    @Test
    @DisplayName("a filter set with no clean equivalent is noindexed")
    void arbitraryFiltersAreNoindex() throws Exception {
        // There is no landing page for "Powai, lift, semi-furnished", and no
        // searcher it would serve. Nothing to consolidate into, so keep it out.
        assertThat(body("/flats?area=Powai&amenity=LIFT&furnishing=SEMI_FURNISHED"))
                .contains("content=\"noindex, follow\"");
    }

    @Test
    @DisplayName("a budget nobody has a page for does not canonicalise to one")
    void oddBudgetDoesNotCanonicalise() throws Exception {
        // ₹27,350 off a slider. Canonicalising it to /flats-in-powai-under-30000
        // would claim two URLs are the same page while they filter differently.
        assertThat(body("/flats?area=Powai&rentMax=27350"))
                .contains("content=\"noindex, follow\"");
    }

    // ---- Guides -------------------------------------------------------------

    @Test
    @DisplayName("a published guide is indexable, with Article data")
    void publishedGuideIsIndexable() throws Exception {
        guides.create("deposits-in-mumbai", "Deposits in Mumbai",
                "What is normal, what is not, and what to get in writing.",
                "## The short version\n\nTwo months is normal. Six is not.",
                "Priya", GuideStatus.PUBLISHED);

        String html = body("/guides/deposits-in-mumbai");

        assertThat(html).contains("content=\"index, follow");
        assertThat(html).contains("\"@type\":\"Article\"");
        assertThat(html).contains("\"datePublished\"");
        assertThat(html).contains("\"name\":\"Priya\"");
        assertThat(html).contains("<h2>The short version</h2>");
        assertThat(html).contains("property=\"og:type\" content=\"article\"");
    }

    @Test
    @DisplayName("a draft guide is a 404, which does not confirm the slug exists")
    void draftGuideIs404() throws Exception {
        guides.create("not-ready-yet", "Not ready", "A summary.", "Some body text.",
                null, GuideStatus.DRAFT);

        mockMvc.perform(get("/guides/not-ready-yet")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("publishing twice does not move the publication date")
    void publicationDateIsStable() {
        UUID id = guides.create("stable-date", "Stable", "A summary.", "Body text.",
                null, GuideStatus.PUBLISHED);

        Instant first = guides.findById(id).orElseThrow().publishedAt();

        // A date that moves every time a typo is fixed tells Google the page is
        // republished rather than maintained.
        guides.update(id, "stable-date", "Stable, corrected", "A summary.", "Body text.",
                null, GuideStatus.PUBLISHED);

        assertThat(guides.findById(id).orElseThrow().publishedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("a published guide reaches the sitemap and a draft does not")
    void sitemapCarriesPublishedGuidesOnly() throws Exception {
        guides.create("in-the-sitemap", "Listed", "A summary.", "Body text.",
                null, GuideStatus.PUBLISHED);
        guides.create("not-in-the-sitemap", "Unlisted", "A summary.", "Body text.",
                null, GuideStatus.DRAFT);

        String xml = body("/sitemap-guides.xml");

        assertThat(xml).contains("https://nexttenant.test/guides/in-the-sitemap");
        assertThat(xml).doesNotContain("not-in-the-sitemap");
    }

    // ---- Fixtures -----------------------------------------------------------

    private String path(Listing listing) {
        return com.nexttenant.listing.ListingUrls.path(listing.getId(), "Powai",
                Configuration.BHK_2.getLabel(), RoomType.PRIVATE_ROOM.getLabel());
    }

    private String body(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Listing publish(User owner, String area, String station) {
        Listing l = new Listing(owner.getId());
        l.setLocationDetails(area, station, (short) 8, (short) 7, true);
        l.setFlatDetails(Configuration.BHK_2, RoomType.PRIVATE_ROOM,
                HandoverType.ROOM_ONLY, (short) 2, Furnishing.SEMI_FURNISHED);
        l.setMoney(28_000, 56_000, null, false, 0, 0);
        l.setAvailableFrom(LocalDate.now().plusDays(30));
        listings.saveAndFlush(l);

        ListingPrivate priv = new ListingPrivate(l.getId());
        priv.placeAt(GeoPoints.of(19.1176, 72.9060));
        listingPrivates.saveAndFlush(priv);

        l.publish(Instant.now(), 14);
        return listings.saveAndFlush(l);
    }
}
