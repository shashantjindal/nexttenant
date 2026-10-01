package com.nexttenant.listing.wizard;

import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.listing.ListingStatus;
import com.nexttenant.photo.PhotoService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two-screen listing wizard.
 *
 * <p>It was six screens asking 33 fields and 15 checkboxes to collect seven
 * required answers, which is why it is now two.
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

        mockMvc.perform(step(id, 1, owner).param("areaName", "Bandra West"));

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

        mockMvc.perform(step(id, 2, stranger).param("monthlyRent", "1"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/list/" + id + "/publish")
                        .with(authentication(as(stranger))).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ---- Editing a published listing --------------------------------------

    private UUID publishedListing() throws Exception {
        UUID id = completeAllSteps();
        mockMvc.perform(post("/list/" + id + "/publish")
                .with(authentication(as(owner))).with(csrf()));
        assertThat(listings.findById(id).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.LIVE);
        return id;
    }

    @Test
    @DisplayName("the owner can reopen and change a published listing")
    void publishedListingIsEditable() throws Exception {
        UUID id = publishedListing();

        // This used to 404: requireOwnedDraft filtered on status = DRAFT, so
        // the "Edit" button on the dashboard led nowhere for every listing
        // anybody had actually published.
        mockMvc.perform(get("/list/" + id + "/2").with(authentication(as(owner))))
                .andExpect(status().isOk());

        mockMvc.perform(step(id, 2, owner)
                        .param("monthlyRent", "35000").param("deposit", "70000"))
                .andExpect(status().is3xxRedirection());

        Listing edited = listings.findById(id).orElseThrow();
        assertThat(edited.getMonthlyRentInr()).isEqualTo(35000);
        // Still live, and still the SAME publication. Re-running publish()
        // would reset published_at and expires_at, silently pushing the listing
        // back to the top of "Newest" and granting a fresh fortnight — fixing
        // a typo is not republishing.
        assertThat(edited.getStatus()).isEqualTo(ListingStatus.LIVE);
    }

    @Test
    @DisplayName("editing does not restart the publication clock")
    void editingKeepsTheOriginalPublication() throws Exception {
        UUID id = publishedListing();
        Listing before = listings.findById(id).orElseThrow();
        java.time.Instant publishedAt = before.getPublishedAt();
        java.time.Instant expiresAt = before.getExpiresAt();

        mockMvc.perform(step(id, 2, owner)
                .param("monthlyRent", "31000").param("deposit", "62000"));
        // The button at the end of the edit flow posts publish; it must be a
        // no-op on something already live.
        mockMvc.perform(post("/list/" + id + "/publish")
                .with(authentication(as(owner))).with(csrf()));

        Listing after = listings.findById(id).orElseThrow();
        assertThat(after.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(after.getExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("a published listing cannot be edited into an incomplete one")
    void editCannotBreakALiveListing() throws Exception {
        UUID id = publishedListing();

        // Clearing the rent on a LIVE listing would violate
        // listings_live_complete_ck. The application refuses FIRST, because a
        // constraint violation marks the transaction rollback-only and cannot
        // be caught and turned into a message — the request would die at commit
        // with an error naming neither the field nor the page.
        mockMvc.perform(step(id, 2, owner).param("monthlyRent", "").param("deposit", ""))
                .andExpect(status().is3xxRedirection());

        Listing unchanged = listings.findById(id).orElseThrow();
        assertThat(unchanged.getMonthlyRentInr()).isEqualTo(32000);
        assertThat(unchanged.getStatus()).isEqualTo(ListingStatus.LIVE);
    }

    @Test
    @DisplayName("a draft may still be left incomplete — that is what a draft is")
    void draftsMayBeIncomplete() throws Exception {
        UUID id = startDraft(owner);

        // The same POST that is refused on a live listing is fine here. The
        // rule is about what may be PUBLISHED, not about what may be typed.
        mockMvc.perform(step(id, 2, owner).param("monthlyRent", "").param("deposit", ""))
                .andExpect(status().is3xxRedirection());

        assertThat(listings.findById(id).orElseThrow().getMonthlyRentInr()).isNull();
    }

    @Test
    @DisplayName("a stranger still cannot edit a published listing")
    void publishedListingIsStillOwned() throws Exception {
        UUID id = publishedListing();

        // Widening which statuses are editable must not widen WHO may edit
        // them. 404, not 403 — a 403 would confirm the listing exists.
        mockMvc.perform(get("/list/" + id + "/2").with(authentication(as(stranger))))
                .andExpect(status().isNotFound());
        mockMvc.perform(step(id, 2, stranger).param("monthlyRent", "1"))
                .andExpect(status().isNotFound());

        assertThat(listings.findById(id).orElseThrow().getMonthlyRentInr()).isEqualTo(32000);
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

    // ---- Finishing is publishing -------------------------------------------

    @Test
    @DisplayName("finishing the last screen puts the listing live, with nothing left to press")
    void finishingTheLastScreenPublishes() throws Exception {
        UUID id = startDraft(owner);
        fillScreenOne(id);
        for (int i = 0; i < PhotoService.MIN_PHOTOS_TO_PUBLISH; i++) {
            photos.upload(id, tinyJpeg(), "room" + i + ".jpg");
        }

        // No POST to /publish anywhere in this test. That button was a second
        // deliberate decision asked of somebody who had already answered every
        // question, and anyone who reached it and did not press it had done
        // the whole job for nothing.
        mockMvc.perform(step(id, 2, owner)
                        .param("monthlyRent", "32000").param("deposit", "64000")
                        .param("brokerage", "0").param("buyout", "0"))
                .andExpect(redirectedUrl("/list/" + id + "/preview"));

        Listing published = listings.findById(id).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(ListingStatus.LIVE);
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("finishing with something still missing does not publish")
    void anIncompleteListingStaysADraft() throws Exception {
        UUID id = startDraft(owner);
        fillScreenOne(id);
        // No photos: the one requirement that cannot be met by typing.

        mockMvc.perform(step(id, 2, owner)
                .param("monthlyRent", "32000").param("deposit", "64000")
                .param("brokerage", "0").param("buyout", "0"));

        assertThat(listings.findById(id).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.DRAFT);

        // And the page says what is outstanding, with a button that will work
        // once it is not. Without this, somebody who fixed the last thing
        // would have no way to go live at all.
        String html = mockMvc.perform(get("/list/" + id + "/preview")
                        .with(authentication(as(owner))))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Still needed before this can go live");
        assertThat(html).contains("Publish listing");
    }

    @Test
    @DisplayName("a live listing's preview offers editing, not publishing")
    void aLivePreviewOffersEditing() throws Exception {
        UUID id = completeAllSteps();
        mockMvc.perform(post("/list/" + id + "/publish")
                .with(authentication(as(owner))).with(csrf()));

        String html = mockMvc.perform(get("/list/" + id + "/preview")
                        .with(authentication(as(owner))))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Your listing is live");
        assertThat(html).contains("Edit listing");
        assertThat(html).contains("View it live");
        // Offering to publish something already published is the kind of
        // button people press, wonder about, and then go looking for what it
        // did.
        assertThat(html).doesNotContain("Publish listing");
    }

    @Test
    @DisplayName("money fields accept what people actually type")
    void moneyParsingIsLenient() throws Exception {
        UUID id = startDraft(owner);
        mockMvc.perform(step(id, 2, owner)
                // The rest of the site renders rents with separators, so
                // rejecting them here would be technically defensible and
                // irritating.
                .param("monthlyRent", "32,000")
                .param("deposit", "₹64000")
                .param("buyout", "15000"));

        // Maintenance moved to the optional screen when the wizard shrank to
        // two. It goes through the same parser, which is the point of checking
        // it here rather than letting the two doorways drift apart.
        mockMvc.perform(post("/list/" + id + "/more")
                .with(authentication(as(owner))).with(csrf())
                .param("maintenance", " 2500 "));

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
    @DisplayName("the area places the flat; there is no pin to get wrong")
    void theAreaPlacesTheFlat() throws Exception {
        UUID id = startDraft(owner);
        mockMvc.perform(step(id, 1, owner).param("areaName", "Bandra West"));

        // This replaces a test for a pin outside Mumbai. There is no longer a
        // way to enter a coordinate at all, so the class of mistake it guarded
        // against � a swapped latitude and longitude landing the flat in the
        // Indian Ocean � cannot be made.
        var priv = drafts.privateFor(id).orElseThrow();
        assertThat(priv.getExactLocation()).isNotNull();
        assertThat(priv.getLocationSource())
                .isEqualTo(com.nexttenant.listing.LocationSource.AREA_CENTROID);
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
    @DisplayName("the exact point stays private and the public one is derived from it")
    void locationGoesToPrivateAndTriggersTheJitter() throws Exception {
        UUID id = completeAllSteps();

        var priv = drafts.privateFor(id).orElseThrow();
        assertThat(priv.getExactLocation()).isNotNull();

        // The trigger ran: the public column is populated from the private
        // one, by the database, with no Java in the path that could skip it.
        assertThat(listings.findById(id).orElseThrow().getApproxLocation()).isNotNull();
    }

    // ---- Helper -----------------------------------------------------------

    private UUID completeAllSteps() throws Exception {
        UUID id = startDraft(owner);
        fillScreenOne(id);
        fillScreenTwo(id);
        fillOptionalDetail(id);

        // Photos are required to publish, uploaded through PhotoService rather
        // than as multipart because what is under test here is the wizard, not
        // the upload plumbing.
        for (int i = 0; i < PhotoService.MIN_PHOTOS_TO_PUBLISH; i++) {
            photos.upload(id, tinyJpeg(), "room" + i + ".jpg");
        }
        return id;
    }

    /** The eight compulsory answers, as the first screen posts them. */
    private void fillScreenOne(UUID id) throws Exception {
        mockMvc.perform(step(id, 1, owner)
                .param("areaName", "Bandra West")
                .param("configuration", "BHK_2")
                .param("roomType", "PRIVATE_ROOM")
                .param("furnishing", "SEMI_FURNISHED")
                .param("flatType", "GATED_SOCIETY")
                .param("flatmateGender", "ANY")
                .param("availableFrom", "2026-11-01")
                .param("existingFlatmates", "2"));
    }

    /**
     * The optional screen.
     *
     * <p>Included because this helper is named for producing a COMPLETE
     * listing, and since the wizard shrank to two screens a published listing
     * no longer has handover terms unless somebody goes and adds them. That is
     * the intended product behaviour; it is not what a fixture called
     * "complete" should mean.
     */
    private void fillOptionalDetail(UUID id) throws Exception {
        mockMvc.perform(post("/list/" + id + "/more")
                .with(authentication(as(owner))).with(csrf())
                .param("handoverType", "ROOM_WITH_FURNITURE")
                .param("amenities", "LIFT").param("amenities", "WIFI")
                .param("floor", "7").param("hasLift", "true")
                .param("societyName", "Sunbeam Apartments")
                .param("fullAddress", "Flat 702, 14th Road, Bandra West")
                .param("ownerInformed", "true")
                .param("depositPaidTo", "EXISTING_TENANT")
                .param("registeredAgreement", "true")
                .param("agreementHandling", "TRANSFER_EXISTING")
                .param("noticePeriodDays", "30")
                .param("bachelorsAllowed", "true"));
    }

    private void fillScreenTwo(UUID id) throws Exception {
        mockMvc.perform(step(id, 2, owner)
                .param("monthlyRent", "32000").param("deposit", "64000")
                .param("brokerage", "0").param("buyout", "15000")
                .param("description", "Easy-going flatmates."));
    }

    /** A minimal valid JPEG. */
    private static byte[] tinyJpeg() throws Exception {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(60, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    // ---- Rendering --------------------------------------------------------

    /**
     * Every wizard page must actually parse.
     *
     * <p>This test exists because of a bug it would have caught on day one.
     * {@code step1.html} contained a malformed Thymeleaf expression — a nested
     * {@code ${...}} inside a ternary branch — and nothing failed, because
     * Thymeleaf compiles a template lazily on FIRST RENDER. Every other test
     * here POSTed to the steps and read the redirect; none of them ever asked
     * for the HTML. The template was broken for as long as it existed and the
     * suite was green throughout.
     *
     * <p>The lesson generalises past this one file: a template is code that is
     * only compiled when something looks at it, so "the handler works" and
     * "the page renders" are two different claims and need two different
     * assertions.
     */
    @Test
    @DisplayName("every wizard step renders, empty and populated")
    void everyStepRenders() throws Exception {
        UUID id = startDraft(owner);

        for (int n = 1; n <= 2; n++) {
            mockMvc.perform(get("/list/" + id + "/" + n).with(authentication(as(owner))))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/list/" + id + "/preview").with(authentication(as(owner))))
                .andExpect(status().isOk());

        // Now with data, which takes the other branch of every null check —
        // in particular the dropped map pin, whose null guard was the bug.
        fillScreenOne(id);
        fillScreenTwo(id);

        for (int n = 1; n <= 2; n++) {
            mockMvc.perform(get("/list/" + id + "/" + n).with(authentication(as(owner))))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/list/" + id + "/preview").with(authentication(as(owner))))
                .andExpect(status().isOk());

        // And the optional screen, which is four of the old six on one page
        // and is the one nobody would notice was broken.
        mockMvc.perform(get("/list/" + id + "/more").with(authentication(as(owner))))
                .andExpect(status().isOk());
    }
}
