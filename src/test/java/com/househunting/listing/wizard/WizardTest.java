package com.househunting.listing.wizard;

import com.househunting.auth.AuthenticatedUser;
import com.househunting.listing.Listing;
import com.househunting.listing.ListingRepository;
import com.househunting.listing.ListingStatus;
import com.househunting.photo.PhotoService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
 * The six-step listing wizard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class WizardTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private ListingDraftService drafts;
    @Autowired private PhotoService photos;

    private User owner;
    private User stranger;

    @BeforeEach
    void createUsers() {
        owner = users.save(new User("Owner", Gender.FEMALE, AgeBracket.AGE_23_27));
        stranger = users.save(new User("Stranger", Gender.MALE, AgeBracket.AGE_28_32));
    }

    /** Signs a request as the given user, the way SessionLogin does in production. */
    private static Authentication as(User user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(user.getId(), user.getFirstName(), user.isAdmin()),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private UUID startDraft(User user) throws Exception {
        mockMvc.perform(get("/list").with(authentication(as(user))))
                .andExpect(status().is3xxRedirection());
        return drafts.currentDraft(user.getId()).getId();
    }

    private MockHttpServletRequestBuilder step(UUID id, int n, User user) {
        return post("/list/" + id + "/" + n).with(authentication(as(user))).with(csrf());
    }

    // ---- Access -----------------------------------------------------------

    @Test
    @DisplayName("the wizard requires a signed-in user")
    void wizardRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/list"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("starting the wizard creates one draft and resumes it thereafter")
    void oneDraftPerPerson() throws Exception {
        UUID first = startDraft(owner);
        UUID second = startDraft(owner);

        // Someone who abandons and returns resumes the same row rather than
        // accumulating half-finished listings nobody will publish.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("a draft resumes at the step it reached")
    void draftResumesAtItsStep() throws Exception {
        UUID id = startDraft(owner);

        mockMvc.perform(step(id, 1, owner)
                .param("areaName", "Bandra West")
                .param("latitude", "19.0596")
                .param("longitude", "72.8295"));

        // wizard_step is a column, not a session attribute, so the position
        // survives a new browser, a new device and a server restart.
        mockMvc.perform(get("/list").with(authentication(as(owner))))
                .andExpect(redirectedUrlPattern("/list/*/2"));
    }

    // ---- Ownership --------------------------------------------------------

    @Test
    @DisplayName("a stranger cannot read, edit or publish someone else's draft")
    void ownershipIsEnforced() throws Exception {
        UUID id = startDraft(owner);

        // 404, not 403: a 403 confirms the listing exists.
        mockMvc.perform(get("/list/" + id + "/1").with(authentication(as(stranger))))
                .andExpect(status().isNotFound());

        mockMvc.perform(step(id, 3, stranger).param("monthlyRent", "1"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/list/" + id + "/publish")
                        .with(authentication(as(stranger))).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a published listing is no longer editable through the wizard")
    void publishedListingLeavesTheWizard() throws Exception {
        UUID id = completeAllSteps();
        mockMvc.perform(post("/list/" + id + "/publish")
                .with(authentication(as(owner))).with(csrf()));

        // requireOwnedDraft filters on status = DRAFT, so a live listing is
        // not reachable here at all. Editing a live listing will be a separate,
        // deliberate flow.
        mockMvc.perform(get("/list/" + id + "/3").with(authentication(as(owner))))
                .andExpect(status().isNotFound());
    }

    // ---- The happy path ---------------------------------------------------

    @Test
    @DisplayName("all six steps produce a publishable listing")
    void fullWizardPublishes() throws Exception {
        UUID id = completeAllSteps();

        mockMvc.perform(post("/list/" + id + "/publish")
                        .with(authentication(as(owner))).with(csrf()))
                .andExpect(status().is3xxRedirection());

        Listing published = listings.findById(id).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(ListingStatus.LIVE);
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getExpiresAt()).isNotNull();
        assertThat(published.getMonthlyRentInr()).isEqualTo(32000);
    }

    @Test
    @DisplayName("money fields accept what people actually type")
    void moneyParsingIsLenient() throws Exception {
        UUID id = startDraft(owner);
        mockMvc.perform(step(id, 3, owner)
                // The rest of the site renders rents with separators, so
                // rejecting them here would be technically defensible and
                // irritating.
                .param("monthlyRent", "32,000")
                .param("deposit", "₹64000")
                .param("maintenance", " 2500 ")
                .param("buyout", "15000"));

        Listing draft = listings.findById(id).orElseThrow();
        assertThat(draft.getMonthlyRentInr()).isEqualTo(32000);
        assertThat(draft.getDepositInr()).isEqualTo(64000);
        assertThat(draft.getMaintenanceInr()).isEqualTo(2500);
        assertThat(draft.getBuyoutInr()).isEqualTo(15000);
    }

    // ---- Validation -------------------------------------------------------

    @Test
    @DisplayName("publishing an incomplete listing is refused, naming what is missing")
    void incompletePublishIsRefused() throws Exception {
        UUID id = startDraft(owner);

        String html = mockMvc.perform(post("/list/" + id + "/publish")
                        .with(authentication(as(owner))).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Still needed before this can go live");
        assertThat(html).contains("the monthly rent");

        assertThat(listings.findById(id).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.DRAFT);
    }

    @Test
    @DisplayName("a pin outside Mumbai is rejected rather than stored")
    void pinMustBeInMumbai() throws Exception {
        UUID id = startDraft(owner);

        // Swapped latitude and longitude are both valid coordinates, so only a
        // bounds check notices — this one lands in the Indian Ocean.
        String html = mockMvc.perform(step(id, 1, owner)
                        .param("areaName", "Bandra West")
                        .param("latitude", "72.8295")
                        .param("longitude", "19.0596"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("outside Mumbai");
        assertThat(drafts.privateFor(id).orElseThrow().getExactLocation()).isNull();
    }

    // ---- Privacy ----------------------------------------------------------

    @Test
    @DisplayName("the preview shows the public view, not the owner's private data")
    void previewIsThePublicView() throws Exception {
        UUID id = completeAllSteps();

        String html = mockMvc.perform(get("/list/" + id + "/preview").with(authentication(as(owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // "This is exactly what someone looking sees" — so it must not show
        // what the owner typed into the private fields.
        assertThat(html).doesNotContain("Sunbeam Apartments");
        assertThat(html).doesNotContain("14th Road");
        assertThat(html).doesNotContain("19.0596");
        assertThat(html).doesNotContain("72.8295");

        // And it says so explicitly, at the moment that promise matters most.
        assertThat(html).contains("What is NOT on this page");
    }

    @Test
    @DisplayName("the pin is stored privately and the public point is derived from it")
    void pinGoesToPrivateAndTriggersTheJitter() throws Exception {
        UUID id = completeAllSteps();

        var priv = drafts.privateFor(id).orElseThrow();
        assertThat(priv.getExactLocation()).isNotNull();
        assertThat(priv.getSocietyName()).isEqualTo("Sunbeam Apartments");

        // The trigger ran: the public column is populated, and differs.
        Listing draft = listings.findById(id).orElseThrow();
        assertThat(draft.getApproxLocation()).isNotNull();
        assertThat(com.househunting.geo.GeoPoints.latitudeOf(draft.getApproxLocation()))
                .isNotEqualTo(19.0596);
    }

    // ---- Helper -----------------------------------------------------------

    private UUID completeAllSteps() throws Exception {
        // Throws from tinyJpeg(); declared on the method signature already.
        UUID id = startDraft(owner);

        mockMvc.perform(step(id, 1, owner)
                .param("areaName", "Bandra West").param("station", "Bandra")
                .param("walkMinutes", "8").param("floor", "7").param("hasLift", "true")
                .param("latitude", "19.0596").param("longitude", "72.8295")
                .param("societyName", "Sunbeam Apartments")
                .param("fullAddress", "Flat 702, 14th Road, Bandra West"));

        mockMvc.perform(step(id, 2, owner)
                .param("configuration", "BHK_2").param("roomType", "PRIVATE_ROOM")
                .param("handoverType", "ROOM_WITH_FURNITURE")
                .param("existingFlatmates", "2").param("furnishing", "SEMI_FURNISHED")
                .param("amenities", "LIFT").param("amenities", "WIFI"));

        mockMvc.perform(step(id, 3, owner)
                .param("monthlyRent", "32000").param("deposit", "64000")
                .param("maintenance", "2500").param("buyout", "15000"));

        mockMvc.perform(step(id, 4, owner)
                .param("ownerInformed", "true").param("depositPaidTo", "EXISTING_TENANT")
                .param("registeredAgreement", "true")
                .param("agreementHandling", "TRANSFER_EXISTING")
                .param("noticePeriodDays", "30").param("bachelorsAllowed", "true"));

        mockMvc.perform(step(id, 5, owner)
                .param("availableFrom", "2026-11-01")
                .param("flatmateAgeMin", "22").param("flatmateAgeMax", "32")
                .param("flatmateGender", "ANY"));

        // Step 6 is photos, and publication needs the minimum. Uploaded
        // through PhotoService rather than as multipart, because what is under
        // test here is the wizard, not the upload plumbing.
        for (int i = 0; i < PhotoService.MIN_PHOTOS_TO_PUBLISH; i++) {
            photos.upload(id, tinyJpeg(), "room" + i + ".jpg");
        }

        return id;
    }

    /** A minimal valid JPEG. */
    private static byte[] tinyJpeg() throws Exception {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(60, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }
}
