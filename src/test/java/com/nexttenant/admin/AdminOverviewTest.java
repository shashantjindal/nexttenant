package com.nexttenant.admin;

import com.nexttenant.auth.AuthenticatedUser;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin landing page.
 *
 * <p>{@code /admin} was a 404 while {@code /admin/reports} worked, so the
 * moderation queue was reachable only by somebody who already knew the URL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminOverviewTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminOverviewService overview;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;

    private User admin;
    private User ordinary;

    @BeforeEach
    void setUp() {
        admin = users.saveAndFlush(new User("Priya", Gender.FEMALE, AgeBracket.AGE_23_27));
        ordinary = users.saveAndFlush(new User("Rahul", Gender.MALE, AgeBracket.AGE_28_32));
    }

    /**
     * Signs somebody in, admin or not.
     *
     * <p>The flag is passed rather than read from the row because {@code User}
     * has no setter for it — V9 grants admin by direct database access only,
     * on the grounds that a method to promote a user is a method that can be
     * called from somewhere it should not be. Authorisation here comes from
     * the granted authority and the principal, which is exactly what
     * SecurityConfig and the templates read.
     */
    private static Authentication signedIn(User user, boolean isAdmin) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(user.getId(), user.getFirstName(), isAdmin),
                null, List.of(new SimpleGrantedAuthority(
                        isAdmin ? "ROLE_ADMIN" : "ROLE_USER")));
    }

    // ---- Access ------------------------------------------------------------

    @Test
    @DisplayName("an admin gets the page")
    void adminSeesIt() throws Exception {
        mockMvc.perform(get("/admin").with(authentication(signedIn(admin, true))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an ordinary signed-in user does not")
    void ordinaryUsersAreRefused() throws Exception {
        // SecurityConfig matches /admin/** to hasRole("ADMIN") before
        // anyRequest(), so a route added under that prefix is protected by
        // default. This is what proves the new one inherited that rather than
        // needing an annotation somebody could forget.
        mockMvc.perform(get("/admin").with(authentication(signedIn(ordinary, false))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a signed-out visitor is sent to log in")
    void signedOutIsRedirected() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    // ---- The link ----------------------------------------------------------

    @Test
    @DisplayName("only an admin is shown the link to it")
    void theLinkIsAdminOnly() throws Exception {
        String asAdmin = mockMvc.perform(get("/").with(authentication(signedIn(admin, true))))
                .andReturn().getResponse().getContentAsString();
        String asUser = mockMvc.perform(get("/").with(authentication(signedIn(ordinary, false))))
                .andReturn().getResponse().getContentAsString();
        String signedOut = mockMvc.perform(get("/"))
                .andReturn().getResponse().getContentAsString();

        assertThat(asAdmin).contains("href=\"/admin\"");
        // Not a security boundary — SecurityConfig is — but advertising a
        // route nobody may use invites people to try it.
        assertThat(asUser).doesNotContain("href=\"/admin\"");
        assertThat(signedOut).doesNotContain("href=\"/admin\"");
    }

    // ---- The numbers -------------------------------------------------------

    @Test
    @DisplayName("counts reflect what is actually in the database")
    void countsAreReal() {
        publish(admin);

        AdminOverview before = overview.snapshot();
        assertThat(before.liveListings()).isGreaterThanOrEqualTo(1);
        assertThat(before.users()).isGreaterThanOrEqualTo(2);

        // Both accounts were created moments ago, so both are "this week".
        assertThat(before.usersThisWeek()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a draft counts as a draft, not as a live room")
    void draftsAreSeparate() {
        Listing draft = new Listing(ordinary.getId());
        draft.advanceWizardTo(3);
        listings.saveAndFlush(draft);

        AdminOverview snapshot = overview.snapshot();

        // The distinction is the point of showing drafts at all: a room
        // nobody can find is not supply, and counting it as such would make
        // an empty marketplace look busy.
        assertThat(snapshot.draftListings()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the draft percentage does not divide by zero on an empty site")
    void draftPercentageSurvivesAnEmptySite() {
        // Launch day, and the first page load an operator ever makes.
        assertThat(new AdminOverview(0, 0, 0, 0, 0, 0, 0, 0).draftPercentage()).isZero();
        assertThat(new AdminOverview(0, 0, 3, 1, 0, 0, 0, 0).draftPercentage()).isEqualTo(25);
    }

    @Test
    @DisplayName("open reports are what marks the page as needing a person")
    void attentionIsDrivenByReports() {
        assertThat(new AdminOverview(9, 9, 9, 9, 9, 9, 9, 0).needsAttention()).isFalse();
        assertThat(new AdminOverview(0, 0, 0, 0, 0, 0, 0, 1).needsAttention()).isTrue();
    }

    @Test
    @DisplayName("the page renders the counts rather than placeholders")
    void thePageShowsTheNumbers() throws Exception {
        publish(admin);

        String html = mockMvc.perform(get("/admin").with(authentication(signedIn(admin, true))))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("rooms live");
        assertThat(html).contains("connections made");
        assertThat(html).contains("open reports");
        // The design-time literals in the template must not survive rendering.
        assertThat(html).doesNotContain(">2 reports waiting<");
    }

    // ---- Helpers -----------------------------------------------------------

    private void publish(User owner) {
        Listing l = new Listing(owner.getId());
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
        listings.saveAndFlush(l);
    }

}
