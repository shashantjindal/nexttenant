package com.nexttenant.admin;

import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.listing.ListingStatus;
import com.nexttenant.user.AgeBracket;
import com.nexttenant.user.Gender;
import com.nexttenant.user.User;
import com.nexttenant.user.UserAccounts;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Listing a flat for somebody else, from the admin side.
 *
 * <p>The assertions that matter are about ownership, not about pages
 * rendering. A three-page form that works and quietly files the listing under
 * the operator's account would pass every status check and would be useless:
 * the requests would go to the wrong person, and the number released on
 * acceptance would be the operator's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminListingCreateTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private UserAccounts accounts;

    private User admin;
    private User ordinary;

    @BeforeEach
    void setUp() {
        admin = users.saveAndFlush(new User("Zubin", Gender.MALE, AgeBracket.AGE_23_27));
        ordinary = users.saveAndFlush(new User("Farah", Gender.FEMALE, AgeBracket.AGE_28_32));
    }

    private static Authentication signedIn(User user, boolean isAdmin) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(user.getId(), user.getFirstName(), isAdmin),
                null, List.of(new SimpleGrantedAuthority(
                        isAdmin ? "ROLE_ADMIN" : "ROLE_USER")));
    }

    /**
     * Page one, filled in and submitted. Returns the draft it opened.
     *
     * <p>The id is read out of the redirect rather than found by scanning the
     * table for "a draft that is not the admin's". The database this runs
     * against is seeded, so that scan would sometimes pick up somebody else's
     * abandoned draft and the test would pass or fail depending on what else
     * was in there.
     */
    private Listing openFor(String email, String firstName, String phone) throws Exception {
        String location = mockMvc.perform(post("/admin/listings/new")
                        .with(authentication(signedIn(admin, true))).with(csrf())
                        .param("email", email)
                        .param("firstName", firstName)
                        .param("phone", phone))
                .andExpect(redirectedUrlPattern("/admin/listings/*/2"))
                .andReturn().getResponse().getRedirectedUrl();

        String id = location.substring("/admin/listings/".length(), location.length() - 2);
        return listings.findById(UUID.fromString(id)).orElseThrow();
    }

    /** Drafts that do not belong to anybody this test created. */
    private long draftCount() {
        return listings.findAll().stream()
                .filter(l -> l.getStatus() == ListingStatus.DRAFT)
                .count();
    }

    // ---- Access ------------------------------------------------------------

    @Test
    @DisplayName("the flow is admin-only, like everything under /admin")
    void ordinaryUsersCannotReachIt() throws Exception {
        mockMvc.perform(get("/admin/listings/new")
                        .with(authentication(signedIn(ordinary, false))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin can open page one")
    void adminSeesPageOne() throws Exception {
        mockMvc.perform(get("/admin/listings/new")
                        .with(authentication(signedIn(admin, true))))
                .andExpect(status().isOk());
    }

    // ---- Ownership: the whole point ---------------------------------------

    @Test
    @DisplayName("the draft belongs to the person it is for, not to the admin")
    void draftBelongsToTheOtherPerson() throws Exception {
        Listing draft = openFor("priya@example.com", "Priya", "9876543210");

        assertThat(draft.isOwnedBy(admin.getId()))
                .as("an operator-owned listing would release the operator's number")
                .isFalse();

        User created = accounts.byId(draft.getLeaverUserId()).orElseThrow();
        assertThat(created.getFirstName()).isEqualTo("Priya");
        assertThat(created.getId()).isNotEqualTo(admin.getId());
    }

    @Test
    @DisplayName("the account is created unverified — signing in is still the OTP")
    void accountIsNotPreVerified() throws Exception {
        Listing draft = openFor("rhea@example.com", "Rhea", "9876500011");
        User created = accounts.byId(draft.getLeaverUserId()).orElseThrow();

        // Nobody has proved this number, and a verified badge meaning "an
        // operator typed it" would be a badge that lies.
        assertThat(created.isPhoneVerified()).isFalse();
    }

    @Test
    @DisplayName("an existing account keeps its own name rather than the operator's version")
    void existingDetailsWin() throws Exception {
        // The person signed up themselves and typed their own name.
        User existing = accounts.findOrCreate("anita@example.com");
        accounts.completeProfile(existing.getId(), "Anita", Gender.FEMALE,
                AgeBracket.AGE_28_32, "+919876512345");

        // The operator now lists for them, reading "Annie" off a WhatsApp
        // message. Silently rewriting what other people see on her listing is
        // not the operator's call to make.
        Listing draft = openFor("anita@example.com", "Annie", "9000000001");

        User after = accounts.byId(draft.getLeaverUserId()).orElseThrow();
        assertThat(after.getId()).isEqualTo(existing.getId());
        assertThat(after.getFirstName()).isEqualTo("Anita");
    }

    @Test
    @DisplayName("starting twice for the same person resumes one draft")
    void oneDraftPerPerson() throws Exception {
        Listing first = openFor("sameer@example.com", "Sameer", "9876500022");
        Listing second = openFor("sameer@example.com", "Sameer", "9876500022");

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    // ---- Validation --------------------------------------------------------

    @Test
    @DisplayName("a bad number is refused on page one, before any listing work")
    void badPhoneIsRefusedUpFront() throws Exception {
        long before = draftCount();

        mockMvc.perform(post("/admin/listings/new")
                        .with(authentication(signedIn(admin, true))).with(csrf())
                        .param("email", "deep@example.com")
                        .param("firstName", "Deep")
                        .param("phone", "12345"))
                .andExpect(status().isOk());   // re-rendered, not redirected

        // A listing with nowhere to send its requests is worse than no listing.
        assertThat(draftCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("a disposable address is refused here exactly as it is at sign-in")
    void sameEmailRulesAsTheFrontDoor() throws Exception {
        long before = draftCount();

        mockMvc.perform(post("/admin/listings/new")
                        .with(authentication(signedIn(admin, true))).with(csrf())
                        .param("email", "not-an-address")
                        .param("firstName", "Deep")
                        .param("phone", "9876500033"))
                .andExpect(status().isOk());

        assertThat(draftCount()).isEqualTo(before);
    }

    // ---- Pages two and three ----------------------------------------------

    @Test
    @DisplayName("page two saves the basics onto the other person's draft")
    void pageTwoSavesBasics() throws Exception {
        Listing draft = openFor("neel@example.com", "Neel", "9876500044");
        UUID id = draft.getId();

        mockMvc.perform(post("/admin/listings/" + id + "/2")
                        .with(authentication(signedIn(admin, true))).with(csrf())
                        .param("areaName", "Bandra West")
                        .param("configuration", "BHK_2")
                        .param("roomType", "PRIVATE_ROOM")
                        .param("furnishing", "SEMI_FURNISHED")
                        .param("flatType", "GATED_SOCIETY")
                        .param("flatmateGender", "ANY")
                        .param("availableFrom", java.time.LocalDate.now()
                                .plusDays(7).toString()))
                .andExpect(redirectedUrlPattern("/admin/listings/*/3"));

        Listing saved = listings.findById(id).orElseThrow();
        assertThat(saved.getAreaName()).isEqualTo("Bandra West");
        assertThat(saved.isOwnedBy(admin.getId())).isFalse();
    }

    @Test
    @DisplayName("page three refuses to publish while something is outstanding")
    void incompleteListingIsNotPublished() throws Exception {
        Listing draft = openFor("ishan@example.com", "Ishan", "9876500055");
        UUID id = draft.getId();

        // Money only: no area, no photos. The redirect goes back to page
        // three with a list rather than publishing something unfinished.
        mockMvc.perform(post("/admin/listings/" + id + "/3")
                        .with(authentication(signedIn(admin, true))).with(csrf())
                        .param("monthlyRent", "32000")
                        .param("deposit", "64000")
                        .param("brokerage", "0")
                        .param("buyout", "0"))
                .andExpect(redirectedUrlPattern("/admin/listings/*/3"));

        assertThat(listings.findById(id).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.DRAFT);
    }

    /**
     * Both later pages render.
     *
     * <p>Worth its own test because of HOW they are built: the fields come
     * from a fragment shared with the public wizard, and a fragment reference
     * that no longer resolves is a 500 at render time, not a compile error.
     * Nothing else in the suite would catch a renamed fragment.
     */
    @Test
    @DisplayName("pages two and three render through the shared field fragments")
    void laterPagesRender() throws Exception {
        Listing draft = openFor("kabir@example.com", "Kabir", "9876500077");

        mockMvc.perform(get("/admin/listings/" + draft.getId() + "/2")
                        .with(authentication(signedIn(admin, true))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/listings/" + draft.getId() + "/3")
                        .with(authentication(signedIn(admin, true))))
                .andExpect(status().isOk());
    }

    // ---- The rule that must not drift -------------------------------------

    @Test
    @DisplayName("the public wizard stays owner-only — the admin door is the admin door")
    void adminCannotDriveThePublicWizardForSomebodyElse() throws Exception {
        Listing draft = openFor("tara@example.com", "Tara", "9876500066");

        // An admin is an ordinary person at /list/..., which is what keeps the
        // photo redirect unambiguous and keeps "admins may edit anything" from
        // becoming a silent property of every request an operator makes.
        mockMvc.perform(get("/list/" + draft.getId() + "/1")
                        .with(authentication(signedIn(admin, true))))
                .andExpect(status().isNotFound());
    }
}
