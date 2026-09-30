package com.nexttenant.dashboard;

import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.geo.GeoPoints;
import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.Furnishing;
import com.nexttenant.listing.HandoverType;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingLifecycle;
import com.nexttenant.listing.ListingPrivate;
import com.nexttenant.listing.ListingPrivateRepository;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.listing.ListingStatus;
import com.nexttenant.listing.RoomType;
import com.nexttenant.shortlist.ShortlistService;
import com.nexttenant.user.AgeBracket;
import com.nexttenant.user.Gender;
import com.nexttenant.user.User;
import com.nexttenant.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dashboard, listing lifecycle and shortlists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.transaction.annotation.Transactional
class DashboardTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;
    @Autowired private ListingLifecycle lifecycle;
    @Autowired private ShortlistService shortlists;
    @Autowired private DashboardService dashboard;
    @Autowired private JdbcClient jdbc;

    private User owner;
    private User stranger;
    private Listing listing;

    @BeforeEach
    void setUp() {
        owner = users.save(new User("Priya", Gender.FEMALE, AgeBracket.AGE_23_27));
        stranger = users.save(new User("Nosy", Gender.MALE, AgeBracket.AGE_28_32));

        listing = new Listing(owner.getId());
        listing.setLocationDetails("Bandra West", "Bandra", (short) 8, (short) 7, true);
        listing.setFlatDetails(Configuration.BHK_2, RoomType.PRIVATE_ROOM,
                HandoverType.ROOM_ONLY, (short) 2, Furnishing.SEMI_FURNISHED);
        listing.setMoney(32000, 64000, null, false, 0, 0);
        listing.setAvailableFrom(LocalDate.now().plusDays(30));
        listings.saveAndFlush(listing);

        ListingPrivate priv = new ListingPrivate(listing.getId());
        priv.placeAt(GeoPoints.of(19.0596, 72.8295));
        listingPrivates.saveAndFlush(priv);

        listing.publish(Instant.now(), 14);
        listings.saveAndFlush(listing);
    }

    private static Authentication as(User user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(user.getId(), user.getFirstName(), user.isAdmin()),
                null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private ListingStatus statusOf(UUID id) {
        return listings.findById(id).orElseThrow().getStatus();
    }

    // ---- Rendering --------------------------------------------------------

    @Test
    @DisplayName("the dashboard shows the owner's listing with its counters")
    void dashboardShowsMyListing() throws Exception {
        jdbc.sql("UPDATE listing_stats SET view_count = 12, shortlist_count = 3 WHERE listing_id = ?")
                .param(listing.getId()).update();

        String html = mockMvc.perform(get("/dashboard").with(authentication(as(owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Priya").contains("₹32,000").contains("Bandra West");
        assertThat(html).contains("12").contains("views");
        assertThat(html).contains("Extend 14 days").contains("Mark as rented");
    }

    @Test
    @DisplayName("a draft is shown so it can be resumed, not hidden")
    void draftsAppearOnTheDashboard() throws Exception {
        Listing draft = listings.saveAndFlush(new Listing(owner.getId()));
        draft.advanceWizardTo(3);
        listings.saveAndFlush(draft);

        String html = mockMvc.perform(get("/dashboard").with(authentication(as(owner))))
                .andReturn().getResponse().getContentAsString();

        // A draft that vanished from the dashboard is a draft nobody finishes.
        assertThat(html).contains("Unfinished listing").contains("step 3 of 6");
    }

    @Test
    @DisplayName("profile strength names a benefit rather than nagging")
    void profileStrengthIsActionable() {
        DashboardService.ProfileStrength strength = dashboard.profileStrength(owner.getId());

        // Phone verified + name + age = 75%; work email outstanding.
        assertThat(strength.percent()).isEqualTo(75);
        assertThat(strength.missing()).anyMatch(m -> m.contains("work email"));
        assertThat(strength.isComplete()).isFalse();
    }

    // ---- Lifecycle --------------------------------------------------------

    @Test
    @DisplayName("extending adds to the existing expiry, not to today")
    void extendAddsToExistingExpiry() {
        Instant before = listings.findById(listing.getId()).orElseThrow().getExpiresAt();

        lifecycle.extend(listing.getId(), owner.getId());

        Instant after = listings.findById(listing.getId()).orElseThrow().getExpiresAt();
        // Extending early must not shorten the listing.
        assertThat(after).isEqualTo(before.plusSeconds(ListingLifecycle.EXTENSION_DAYS * 86400L));
    }

    @Test
    @DisplayName("pausing hides the listing publicly; resuming brings it back")
    void pauseAndResume() throws Exception {
        mockMvc.perform(post("/listings/" + listing.getId() + "/pause")
                .with(authentication(as(owner))).with(csrf()));
        assertThat(statusOf(listing.getId())).isEqualTo(ListingStatus.PAUSED);
        mockMvc.perform(get("/flats/" + listing.getId())).andExpect(status().isNotFound());

        mockMvc.perform(post("/listings/" + listing.getId() + "/resume")
                .with(authentication(as(owner))).with(csrf()));
        assertThat(statusOf(listing.getId())).isEqualTo(ListingStatus.LIVE);
        // 301 rather than 200 because the bare id is not the canonical
        // address any more. What this asserts is unchanged: public again, as
        // against the 404 above. Following the redirect proves the page is
        // really there rather than merely pointed at.
        String canonical = mockMvc.perform(get("/flats/" + listing.getId()))
                .andExpect(status().isMovedPermanently())
                .andReturn().getResponse().getRedirectedUrl();
        mockMvc.perform(get(canonical)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("resuming an expired listing gives it time rather than returning it expired")
    void resumingExpiredExtendsIt() {
        lifecycle.pause(listing.getId(), owner.getId());
        // Force the expiry into the past.
        jdbc.sql("UPDATE listings SET expires_at = now() - interval '2 days' WHERE id = ?")
                .param(listing.getId()).update();

        lifecycle.resume(listing.getId(), owner.getId());

        Listing resumed = listings.findById(listing.getId()).orElseThrow();
        // Otherwise it would come back already expired, vanish from search, and
        // look broken to the owner who just put it back online.
        assertThat(resumed.getExpiresAt()).isAfter(Instant.now());
        assertThat(resumed.getStatus()).isEqualTo(ListingStatus.LIVE);
    }

    @Test
    @DisplayName("marking rented removes it from public view")
    void markRented() throws Exception {
        mockMvc.perform(post("/listings/" + listing.getId() + "/rented")
                .with(authentication(as(owner))).with(csrf()));

        assertThat(statusOf(listing.getId())).isEqualTo(ListingStatus.RENTED);
        mockMvc.perform(get("/flats/" + listing.getId())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the expiry sweep expires only overdue live listings")
    void expirySweep() {
        jdbc.sql("UPDATE listings SET expires_at = now() - interval '1 day' WHERE id = ?")
                .param(listing.getId()).update();

        int expired = lifecycle.expireOverdue();

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(listing.getId())).isEqualTo(ListingStatus.EXPIRED);
    }

    // ---- Ownership --------------------------------------------------------

    @Test
    @DisplayName("a stranger cannot touch someone else's listing")
    void lifecycleIsOwnerOnly() throws Exception {
        for (String action : List.of("extend", "pause", "rented", "take-down")) {
            mockMvc.perform(post("/listings/" + listing.getId() + "/" + action)
                            .with(authentication(as(stranger))).with(csrf()))
                    .andExpect(status().isNotFound());
        }
        assertThat(statusOf(listing.getId())).isEqualTo(ListingStatus.LIVE);

        assertThatThrownBy(() -> lifecycle.takeDown(listing.getId(), stranger.getId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    // ---- Shortlists -------------------------------------------------------

    @Test
    @DisplayName("shortlisting is idempotent, so a double tap is harmless")
    void shortlistIsIdempotent() {
        assertThat(shortlists.add(stranger.getId(), listing.getId())).isTrue();
        // The heart is a button people double-tap on a phone. Without ON
        // CONFLICT DO NOTHING the second tap is a 500 for something harmless.
        assertThat(shortlists.add(stranger.getId(), listing.getId())).isFalse();

        assertThat(shortlists.countFor(stranger.getId())).isEqualTo(1);
        assertThat(count(listing.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("toggling adds then removes, and the counter follows")
    void shortlistToggle() {
        assertThat(shortlists.toggle(stranger.getId(), listing.getId())).isTrue();
        assertThat(count(listing.getId())).isEqualTo(1);

        assertThat(shortlists.toggle(stranger.getId(), listing.getId())).isFalse();
        assertThat(count(listing.getId())).isZero();
    }

    @Test
    @DisplayName("the shortlist page hides listings that are no longer live")
    void shortlistHidesDeadListings() {
        shortlists.add(stranger.getId(), listing.getId());
        assertThat(shortlists.shortlistedListings(stranger.getId())).hasSize(1);

        lifecycle.markRented(listing.getId(), owner.getId());

        // Flush required, and worth understanding: shortlistedListings() reads
        // through JdbcClient, which bypasses JPA. Hibernate auto-flushes before
        // its OWN queries, not before raw JDBC ones, so without this the SQL
        // below would read the pre-update row. Any place these two are mixed in
        // one transaction needs the same care.
        listings.flush();

        // A shortlisted room that has been rented should disappear rather than
        // sit there as a dead link. The row is kept, so it returns if resumed.
        assertThat(shortlists.shortlistedListings(stranger.getId())).isEmpty();
        assertThat(shortlists.isShortlisted(stranger.getId(), listing.getId())).isTrue();
    }

    @Test
    @DisplayName("shortlisted state for a page is one query, not one per card")
    void shortlistBatchLookup() {
        shortlists.add(stranger.getId(), listing.getId());

        var ids = List.of(listing.getId(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(shortlists.shortlistedAmong(stranger.getId(), ids))
                .containsExactly(listing.getId());

        assertThat(shortlists.shortlistedAmong(null, ids)).isEmpty();
        assertThat(shortlists.shortlistedAmong(stranger.getId(), List.of())).isEmpty();
    }

    // ---- Open redirect ----------------------------------------------------

    @ParameterizedTest
    @DisplayName("returnTo cannot send someone off-site")
    @ValueSource(strings = {
            "https://evil.example.com",
            "http://evil.example.com/x",
            "//evil.example.com",
            "javascript:alert(1)",
            "\\\\evil.example.com"
    })
    void returnToRejectsOffSiteTargets(String target) throws Exception {
        // An open redirect lets someone craft a link that looks like ours and
        // bounces users to a phishing page carrying our domain in the referrer.
        mockMvc.perform(post("/listings/" + listing.getId() + "/shortlist")
                        .param("returnTo", target)
                        .with(authentication(as(stranger))).with(csrf()))
                .andExpect(redirectedUrl("/flats/" + listing.getId()));
    }

    @ParameterizedTest
    @DisplayName("returnTo accepts ordinary same-site paths")
    @ValueSource(strings = {"/flats", "/shortlist", "/dashboard"})
    void returnToAcceptsSitePaths(String target) throws Exception {
        mockMvc.perform(post("/listings/" + listing.getId() + "/shortlist")
                        .param("returnTo", target)
                        .with(authentication(as(stranger))).with(csrf()))
                .andExpect(redirectedUrl(target));
    }

    private long count(UUID listingId) {
        return jdbc.sql("SELECT shortlist_count FROM listing_stats WHERE listing_id = ?")
                .param(listingId).query(Long.class).single();
    }
}
