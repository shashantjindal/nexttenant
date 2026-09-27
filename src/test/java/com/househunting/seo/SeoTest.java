package com.househunting.seo;

import com.househunting.geo.GeoPoints;
import com.househunting.listing.Configuration;
import com.househunting.listing.Furnishing;
import com.househunting.listing.HandoverType;
import com.househunting.listing.Listing;
import com.househunting.listing.ListingPrivate;
import com.househunting.listing.ListingPrivateRepository;
import com.househunting.listing.ListingRepository;
import com.househunting.listing.RoomType;
import com.househunting.user.AgeBracket;
import com.househunting.user.Gender;
import com.househunting.user.User;
import com.househunting.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * robots.txt and sitemap.xml.
 *
 * <p>Both paths were permitted in SecurityConfig and neither file existed, so
 * both returned 404 — the kind of gap nothing complains about, because the
 * only party that notices is a crawler.
 */
@SpringBootTest(properties = "househunting.site.base-url=https://househunting.test")
@AutoConfigureMockMvc
@Transactional
class SeoTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SitemapService sitemap;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;

    private Listing live;
    private Listing draft;

    @BeforeEach
    void setUp() {
        User leaver = users.save(new User("Priya", Gender.FEMALE, AgeBracket.AGE_23_27));

        live = publish(leaver, "Bandra West", "Bandra");

        // A draft, which must NOT appear: it is not reachable publicly.
        draft = new Listing(leaver.getId());
        draft.advanceWizardTo(2);
        listings.saveAndFlush(draft);
    }

    private Listing publish(User owner, String area, String station) {
        Listing l = new Listing(owner.getId());
        l.setLocationDetails(area, station, (short) 8, (short) 7, true);
        l.setFlatDetails(Configuration.BHK_2, RoomType.PRIVATE_ROOM,
                HandoverType.ROOM_ONLY, (short) 2, Furnishing.SEMI_FURNISHED);
        l.setMoney(32000, 64000, null, false, 0, 0);
        l.setAvailableFrom(LocalDate.now().plusDays(30));
        listings.saveAndFlush(l);

        ListingPrivate priv = new ListingPrivate(l.getId());
        priv.placeAt(GeoPoints.of(19.0596, 72.8295));
        listingPrivates.saveAndFlush(priv);

        l.publish(Instant.now(), 14);
        return listings.saveAndFlush(l);
    }

    // ---- robots.txt -------------------------------------------------------

    @Test
    @DisplayName("robots.txt is served as plain text, not a 404")
    void robotsExists() throws Exception {
        String body = mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).startsWith("User-agent: *");
    }

    @Test
    @DisplayName("robots.txt names the sitemap by absolute URL")
    void robotsPointsAtTheSitemap() throws Exception {
        String body = body("/robots.txt");

        // The whole reason this is generated rather than a static file. A
        // relative path here is ignored — the directive is defined as taking
        // a full URL, and a crawler reading "Sitemap: /sitemap.xml" simply
        // never fetches it.
        assertThat(body).contains("Sitemap: https://househunting.test/sitemap.xml");
    }

    @Test
    @DisplayName("robots.txt keeps crawlers out of signed-in areas")
    void robotsDisallowsPrivateAreas() throws Exception {
        String body = body("/robots.txt");

        assertThat(body).contains("Disallow: /dashboard");
        assertThat(body).contains("Disallow: /requests");
        assertThat(body).contains("Disallow: /admin/");
        assertThat(body).contains("Disallow: /actuator/");
    }

    @Test
    @DisplayName("robots.txt closes the infinite space that paging creates")
    void robotsDisallowsPagingParameters() throws Exception {
        String body = body("/robots.txt");

        // A filtered, sorted, cursor-paged search page is an unbounded number
        // of URLs over a bounded number of listings. Left open, a crawler
        // spends its entire visit on permutations and never reaches the pages
        // that are actually worth indexing.
        assertThat(body).contains("cursor=");
        assertThat(body).contains("sort=");
    }

    @Test
    @DisplayName("browsing itself stays open")
    void robotsAllowsTheBrowseSurface() throws Exception {
        String body = body("/robots.txt");

        // The failure worth guarding against is a blanket "Disallow: /", which
        // silently removes the whole site from search.
        assertThat(body).doesNotContain("Disallow: /\n");
        assertThat(body).contains("Allow: /flats");
    }

    // ---- sitemap.xml ------------------------------------------------------

    @Test
    @DisplayName("sitemap.xml is served as XML, not a 404")
    void sitemapExists() throws Exception {
        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentTypeCompatibleWith("application/xml"));
    }

    @Test
    @DisplayName("the sitemap is well-formed XML in the right namespace")
    void sitemapParses() throws Exception {
        String xml = body("/sitemap.xml");

        // Parsed rather than string-matched, because a sitemap is validated by
        // Google as a whole document: one unescaped ampersand makes it
        // malformed and every URL in it is lost, not just the broken one.
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(doc.getDocumentElement().getNodeName()).isEqualTo("urlset");
        assertThat(doc.getDocumentElement().getAttribute("xmlns"))
                .isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9");
        assertThat(doc.getElementsByTagName("url").getLength()).isGreaterThan(0);
    }

    @Test
    @DisplayName("every live listing is in it, with an absolute URL")
    void sitemapListsLiveListings() throws Exception {
        String xml = body("/sitemap.xml");

        // The listings ARE the search traffic: one page each, every one of
        // them targeting a query with a real intention behind it.
        assertThat(xml).contains("https://househunting.test/flats/" + live.getId());
        assertThat(xml).contains("https://househunting.test/</loc>");
        assertThat(xml).contains("https://househunting.test/flats</loc>");
    }

    @Test
    @DisplayName("a draft is not in it")
    void sitemapExcludesDrafts() throws Exception {
        String xml = body("/sitemap.xml");

        // A sitemap is a claim that these URLs are worth fetching. A draft is
        // not publicly reachable, so listing it spends crawl budget on a 404
        // and is read as a quality signal against the whole document.
        assertThat(xml).doesNotContain(draft.getId().toString());
    }

    @Test
    @DisplayName("a paused listing drops out of it")
    void sitemapFollowsStatus() throws Exception {
        assertThat(body("/sitemap.xml")).contains(live.getId().toString());

        live.pause();
        listings.saveAndFlush(live);

        // The reason this is generated per request rather than written to a
        // file: a listing's status changes daily, and a stale sitemap is worse
        // than none.
        assertThat(body("/sitemap.xml")).doesNotContain(live.getId().toString());
    }

    @Test
    @DisplayName("each entry carries the date it last changed")
    void sitemapCarriesLastMod() throws Exception {
        String xml = body("/sitemap.xml");

        assertThat(xml).contains("<lastmod>");
        // Date-only W3C format, which the protocol allows.
        assertThat(xml).containsPattern("<lastmod>\\d{4}-\\d{2}-\\d{2}</lastmod>");
    }

    @Test
    @DisplayName("it does not carry the two tags Google ignores")
    void sitemapOmitsIgnoredTags() throws Exception {
        String xml = body("/sitemap.xml");

        // Google has said for years that it reads neither. They are what most
        // sitemap tutorials spend their time on, and they are bytes that do
        // nothing.
        assertThat(xml).doesNotContain("<changefreq>");
        assertThat(xml).doesNotContain("<priority>");
    }

    @Test
    @DisplayName("both files are reachable without signing in")
    void bothArePublic() throws Exception {
        // anyRequest().authenticated() means a route is private unless it was
        // deliberately opened. Crawlers do not have sessions, so a redirect to
        // the login page here would make both files useless.
        mockMvc.perform(get("/robots.txt")).andExpect(status().isOk());
        mockMvc.perform(get("/sitemap.xml")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the size is known, so the protocol limit is not hit silently")
    void sizeIsReported() {
        // 50,000 URLs is the limit, past which a sitemap must be split.
        // Nothing splits it yet; this is what makes the day it matters
        // visible rather than a silent truncation.
        assertThat(sitemap.size()).isGreaterThanOrEqualTo(3);
        assertThat(sitemap.nearingLimit()).isFalse();
    }

    private String body(String path) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get(path))
                .andReturn().getResponse().getContentAsString();
    }
}
