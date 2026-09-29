package com.nexttenant.moderation;

import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.geo.GeoPoints;
import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.Furnishing;
import com.nexttenant.listing.HandoverType;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingPrivate;
import com.nexttenant.listing.ListingPrivateRepository;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.listing.ListingStatus;
import com.nexttenant.listing.RoomType;
import com.nexttenant.user.AgeBracket;
import com.nexttenant.user.Gender;
import com.nexttenant.user.User;
import com.nexttenant.user.UserRepository;
import com.nexttenant.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
 * Taking a listing down without a report behind it.
 *
 * <p>Until this existed, a moderator could only act on a listing somebody had
 * reported. One obviously-broker listing from an otherwise fine account meant
 * either removing the whole account or editing the database by hand — and a
 * hand-edited row is a decision with nobody's name on it.
 *
 * <p>The test that matters most here is {@link #doesNotCountAsAStrike}. The
 * two-strike rule is what stops moderation being a weapon, and it rests on two
 * confirmed reports being two different people. A unilateral action that
 * counted toward it would collapse that to one person clicking twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminTakeDownTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ModerationService moderation;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;
    @Autowired private JdbcClient jdbc;

    private User admin;
    private User owner;
    private Listing listing;

    @BeforeEach
    void setUp() {
        admin = users.saveAndFlush(new User("Zubin", Gender.MALE, AgeBracket.AGE_23_27));
        owner = users.saveAndFlush(new User("Farah", Gender.FEMALE, AgeBracket.AGE_28_32));
        listing = publish(owner);
    }

    private static Authentication signedIn(User user, boolean isAdmin) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(user.getId(), user.getFirstName(), isAdmin),
                null, List.of(new SimpleGrantedAuthority(
                        isAdmin ? "ROLE_ADMIN" : "ROLE_USER")));
    }

    // ---- The rule that must not drift --------------------------------------

    @Test
    @DisplayName("taking a listing down is not a strike against the owner")
    void doesNotCountAsAStrike() {
        moderation.takeDownListing(listing.getId(), admin.getId(), "Broker.");
        moderation.takeDownListing(publish(owner).getId(), admin.getId(), "Broker again.");

        // Two unilateral takedowns, and the account is untouched. Were these
        // strikes, STRIKES_TO_REMOVE is 2 and the owner would now be gone -
        // one moderator, two clicks, no second person involved anywhere.
        assertThat(moderation.strikesAgainst(owner.getId())).isZero();
        assertThat(users.findById(owner.getId()).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    // ---- What it does ------------------------------------------------------

    @Test
    @DisplayName("the listing goes down and stays down")
    void itTakesTheListingDown() {
        assertThat(moderation.takeDownListing(listing.getId(), admin.getId(), "Broker.")).isTrue();

        Listing after = listings.findById(listing.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ListingStatus.TAKEN_DOWN);
        assertThat(after.isPubliclyVisible()).isFalse();
        // Terminal on purpose: unlike PAUSED or EXPIRED there is no button,
        // the owner's or the moderator's, that brings this back.
        assertThat(after.getStatus().isResumable()).isFalse();
    }

    @Test
    @DisplayName("it is recorded against the moderator who did it")
    void itIsAudited() {
        moderation.takeDownListing(listing.getId(), admin.getId(), "Same photos as three others.");

        var row = jdbc.sql("""
                        SELECT actor_id, action, target_type, note
                          FROM moderation_actions
                         WHERE target_id = ? AND action = 'LISTING_TAKEN_DOWN'
                        """)
                .param(listing.getId())
                .query((rs, n) -> List.of(
                        rs.getObject("actor_id", UUID.class).toString(),
                        rs.getString("target_type"),
                        rs.getString("note")))
                .single();

        assertThat(row).containsExactly(
                admin.getId().toString(), "LISTING", "Same photos as three others.");
    }

    @Test
    @DisplayName("doing it twice reports the second as a no-op")
    void secondTimeChangesNothing() {
        moderation.takeDownListing(listing.getId(), admin.getId(), "Broker.");

        // False rather than an exception, so the page can say "already down"
        // instead of claiming to have done something it did not do.
        assertThat(moderation.takeDownListing(listing.getId(), admin.getId(), "Broker.")).isFalse();

        Long audited = jdbc.sql("""
                        SELECT count(*) FROM moderation_actions
                         WHERE target_id = ? AND action = 'LISTING_TAKEN_DOWN'
                        """)
                .param(listing.getId())
                .query(Long.class)
                .single();
        assertThat(audited).isEqualTo(1);
    }

    // ---- The reason --------------------------------------------------------

    @Test
    @DisplayName("a reason is required, and whitespace is not a reason")
    void reasonIsRequired() {
        // Unlike confirm(), where the report itself says who complained and
        // why, nothing here records the motive except this field. An empty one
        // leaves an audit row saying somebody did something.
        for (String nothing : new String[]{null, "", "   "}) {
            assertThatThrownBy(() ->
                    moderation.takeDownListing(listing.getId(), admin.getId(), nothing))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.LIVE);
    }

    // ---- Access ------------------------------------------------------------

    @Test
    @DisplayName("an ordinary user cannot reach the form or post to it")
    void ordinaryUsersAreRefused() throws Exception {
        var them = authentication(signedIn(owner, false));

        mockMvc.perform(get("/admin/listings/" + listing.getId() + "/take-down").with(them))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/listings/" + listing.getId() + "/take-down")
                        .with(them).with(csrf()).param("reason", "mine now"))
                .andExpect(status().isForbidden());

        // The owner of a listing is not a moderator of it either.
        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.LIVE);
    }

    @Test
    @DisplayName("a post without a CSRF token does nothing")
    void csrfIsRequired() throws Exception {
        mockMvc.perform(post("/admin/listings/" + listing.getId() + "/take-down")
                        .with(authentication(signedIn(admin, true)))
                        .param("reason", "Broker."))
                .andExpect(status().isForbidden());

        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.LIVE);
    }

    // ---- The pages ---------------------------------------------------------

    @Test
    @DisplayName("the form warns that it cannot be undone")
    void theFormWarns() throws Exception {
        String html = mockMvc.perform(get("/admin/listings/" + listing.getId() + "/take-down")
                        .with(authentication(signedIn(admin, true))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("This cannot be undone");
        assertThat(html).contains("Bandra West");
    }

    @Test
    @DisplayName("confirming returns to the filtered page the moderator came from")
    void itReturnsToWhereYouWere() throws Exception {
        // Losing the filter would drop a moderator working through DRAFT back
        // at the top of an unfiltered list every time they acted.
        mockMvc.perform(post("/admin/listings/" + listing.getId() + "/take-down")
                        .with(authentication(signedIn(admin, true))).with(csrf())
                        .param("reason", "Broker.")
                        .param("status", "LIVE")
                        .param("page", "2"))
                .andExpect(redirectedUrl("/admin/listings?status=LIVE&page=2"));
    }

    @Test
    @DisplayName("an empty reason goes back to the form, not on to the list")
    void anEmptyReasonReturnsToTheForm() throws Exception {
        mockMvc.perform(post("/admin/listings/" + listing.getId() + "/take-down")
                        .with(authentication(signedIn(admin, true))).with(csrf())
                        .param("reason", "   ")
                        .param("status", "LIVE"))
                .andExpect(redirectedUrl(
                        "/admin/listings/" + listing.getId() + "/take-down?status=LIVE&page=0"));

        assertThat(listings.findById(listing.getId()).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.LIVE);
    }

    @Test
    @DisplayName("an already-down listing offers nothing to do")
    void alreadyDownSaysSo() throws Exception {
        moderation.takeDownListing(listing.getId(), admin.getId(), "Broker.");

        String html = mockMvc.perform(get("/admin/listings/" + listing.getId() + "/take-down")
                        .with(authentication(signedIn(admin, true))))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Already down");
        assertThat(html).doesNotContain("This cannot be undone");
    }

    @Test
    @DisplayName("the listings page links to the form, and not for one already down")
    void theListLinksToIt() throws Exception {
        String before = mockMvc.perform(get("/admin/listings")
                        .with(authentication(signedIn(admin, true))))
                .andReturn().getResponse().getContentAsString();
        assertThat(before).contains(listing.getId() + "/take-down");

        moderation.takeDownListing(listing.getId(), admin.getId(), "Broker.");

        String after = mockMvc.perform(get("/admin/listings")
                        .with(authentication(signedIn(admin, true))))
                .andReturn().getResponse().getContentAsString();
        assertThat(after).doesNotContain(listing.getId() + "/take-down");
    }

    // ---- Helpers -----------------------------------------------------------

    private Listing publish(User user) {
        Listing l = new Listing(user.getId());
        l.setLocationDetails("Bandra West", "Bandra", (short) 8, (short) 7, true);
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
}
