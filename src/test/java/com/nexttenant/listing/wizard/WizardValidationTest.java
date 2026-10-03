package com.nexttenant.listing.wizard;

import com.nexttenant.auth.AuthenticatedUser;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingPrivate;
import com.nexttenant.listing.ListingPrivateRepository;
import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.FlatType;
import com.nexttenant.listing.FlatmateGender;
import com.nexttenant.listing.Furnishing;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.listing.RoomType;
import com.nexttenant.listing.LocationSource;
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

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the wizard refuses, and what it no longer demands.
 *
 * <p>All of this came from watching somebody use it. The money fields took
 * "0234ax" and saved ₹234; the date picker offered dates that had already
 * gone; dropping a pin was compulsory and was where listings went to die; and
 * the list of what was still missing named problems without saying where to
 * fix them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WizardValidationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ListingDraftService drafts;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;
    @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private User owner;
    private Listing draft;

    @BeforeEach
    void setUp() {
        owner = users.saveAndFlush(new User("Zubin", Gender.MALE, AgeBracket.AGE_23_27));
        draft = listings.saveAndFlush(new Listing(owner.getId()));
    }

    private Authentication signedIn() {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(owner.getId(), owner.getFirstName(), false),
                null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    // ---- Money -------------------------------------------------------------

    @Test
    @DisplayName("a money field with letters in it is refused, not quietly salvaged")
    void junkInAMoneyFieldIsRefused() throws Exception {
        mockMvc.perform(post("/list/" + draft.getId() + "/2")
                        .with(authentication(signedIn())).with(csrf())
                        .param("monthlyRent", "0234ax")
                        .param("deposit", "64000"))
                .andExpect(redirectedUrl("/list/" + draft.getId() + "/2"))
                .andExpect(flash().attributeExists("error"));

        // The bug this replaces: non-digits were stripped, so "0234ax" became
        // 234 and was saved as the rent. A number the person never typed, on
        // the field that decides what they are asking for, with nothing on
        // screen to say it had happened.
        Listing after = listings.findById(draft.getId()).orElseThrow();
        assertThat(after.getMonthlyRentInr()).isNull();
        assertThat(after.getDepositInr()).isNull();
    }

    @Test
    @DisplayName("the separators people actually type are still accepted")
    void separatorsStillWork() throws Exception {
        // Refusing these would be defensible and would irritate everybody: the
        // rest of the site renders rents with exactly these separators.
        mockMvc.perform(post("/list/" + draft.getId() + "/2")
                        .with(authentication(signedIn())).with(csrf())
                        .param("monthlyRent", "32,000")
                        .param("deposit", "₹ 64000"))
                .andExpect(redirectedUrl("/list/" + draft.getId() + "/preview"));

        Listing after = listings.findById(draft.getId()).orElseThrow();
        assertThat(after.getMonthlyRentInr()).isEqualTo(32000);
        assertThat(after.getDepositInr()).isEqualTo(64000);
    }

    @Test
    @DisplayName("nothing typed is still nothing, not an error")
    void emptyIsNotJunk() throws Exception {
        mockMvc.perform(post("/list/" + draft.getId() + "/2")
                        .with(authentication(signedIn())).with(csrf())
                        .param("monthlyRent", "")
                        .param("deposit", ""))
                .andExpect(redirectedUrl("/list/" + draft.getId() + "/preview"));
    }

    // ---- The move-in date --------------------------------------------------

    @Test
    @DisplayName("a move-in date in the past is refused")
    void pastMoveInDateIsRefused() throws Exception {
        mockMvc.perform(post("/list/" + draft.getId() + "/1")
                        .with(authentication(signedIn())).with(csrf())
                        .param("availableFrom", LocalDate.now().minusMonths(2).toString()))
                .andExpect(redirectedUrl("/list/" + draft.getId() + "/1"))
                .andExpect(flash().attributeExists("error"));

        assertThat(listings.findById(draft.getId()).orElseThrow().getAvailableFrom()).isNull();
    }

    @Test
    @DisplayName("a date already stored can be kept even once it has passed")
    void anExistingPastDateSurvives() throws Exception {
        LocalDate past = LocalDate.now().minusMonths(2);
        draft.setAvailableFrom(past);
        listings.saveAndFlush(draft);

        // A listing published in August for an August move-in still means
        // "available now" in October. Refusing to let its owner save an edit
        // to house rules, over a field they did not touch, would be the
        // validation making the listing worse.
        mockMvc.perform(post("/list/" + draft.getId() + "/1")
                        .with(authentication(signedIn())).with(csrf())
                        .param("availableFrom", past.toString())
                        .param("houseRulesNote", "Quiet after 11"))
                .andExpect(redirectedUrl("/list/" + draft.getId() + "/2"));

        assertThat(listings.findById(draft.getId()).orElseThrow().getAvailableFrom())
                .isEqualTo(past);
    }

    @Test
    @DisplayName("the picker will not offer a date that has gone")
    void thePickerHasAFloor() throws Exception {
        String html = mockMvc.perform(get("/list/" + draft.getId() + "/1")
                        .with(authentication(signedIn())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // IST, matching the controller: at 1am in Mumbai it is still
        // yesterday in UTC, and the picker must not offer a day that has gone.
        assertThat(html).contains("min=\""
                + LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")) + "\"");
    }

    // ---- Where the flat gets placed ----------------------------------------

    /** Screen one, with everything filled in but the area, which varies. */
    private void screenOne(String area) {
        drafts.saveBasics(draft.getId(), Actor.owner(owner.getId()), area,
                Configuration.BHK_2, RoomType.PRIVATE_ROOM, Furnishing.SEMI_FURNISHED,
                FlatType.GATED_SOCIETY, FlatmateGender.ANY,
                com.nexttenant.listing.FlatmateMakeup.MIXED,
                LocalDate.now().plusMonths(1), (short) 2);
    }

    @Test
    @DisplayName("a named area places the flat, so no pin is ever asked for")
    void anAreaIsEnoughToPlaceAFlat() {
        screenOne("Powai");

        ListingPrivate priv = listingPrivates.findById(draft.getId()).orElseThrow();

        // The pin question is gone from the product entirely. It asked
        // somebody to find their own building on a map, on a phone, and it was
        // the commonest place people stopped.
        assertThat(priv.hasLocation()).isTrue();
        assertThat(priv.getLocationSource()).isEqualTo(LocationSource.AREA_CENTROID);

        // Read through SQL, not the repository. The published point is written
        // by a database TRIGGER on listing_private, so the Listing sitting in
        // this transaction's persistence context still holds the value it was
        // loaded with — null — and would fail a check the database passes.
        Long placed = jdbc.sql(
                        "SELECT count(*) FROM listings WHERE id = ? AND approx_location IS NOT NULL")
                .param(draft.getId())
                .query(Long.class)
                .single();
        assertThat(placed).isEqualTo(1);
    }

    @Test
    @DisplayName("the station comes from the area rather than from the person")
    void theStationIsDerived() {
        screenOne("Powai");

        // One of the two questions that disappeared without its answer going
        // with it. Every area already knew its own station.
        assertThat(listings.findById(draft.getId()).orElseThrow().getNearestStation())
                .isNotBlank();
    }

    @Test
    @DisplayName("a derived location says it is derived")
    void aCentroidDoesNotPretendToBeAPin() {
        screenOne("Powai");

        // exact_location is a column whose whole meaning is that it holds the
        // truth. A locality centre in it, unannounced, would leave the table
        // lying about itself — and invisibly, since a centroid looks exactly
        // like a pin.
        assertThat(listingPrivates.findById(draft.getId()).orElseThrow().hasRealPin())
                .isFalse();
    }

    @Test
    @DisplayName("a pin from the old wizard survives a re-save")
    void resavingDoesNotDowngradeARealPin() {
        // Listings created before the pin was removed still carry real ones.
        // Editing such a listing posts no coordinates — there is no longer a
        // map to post them from — and must not quietly replace the building
        // its owner marked with the middle of their neighbourhood.
        ListingPrivate priv = new ListingPrivate(draft.getId());
        priv.placeAt(com.nexttenant.geo.GeoPoints.of(19.1176, 72.9060));
        listingPrivates.saveAndFlush(priv);

        screenOne("Powai");

        ListingPrivate after = listingPrivates.findById(draft.getId()).orElseThrow();
        assertThat(after.hasRealPin()).isTrue();
        assertThat(after.getLocationSource()).isEqualTo(LocationSource.PIN);
    }

    @Test
    @DisplayName("an unrecognised area leaves the flat unplaced rather than guessing")
    void anUnknownAreaPlacesNothing() {
        screenOne("Nowhere In Particular");

        assertThat(listingPrivates.findById(draft.getId())
                .map(ListingPrivate::hasLocation).orElse(false)).isFalse();
    }


    // ---- What is still missing ---------------------------------------------

    @Test
    @DisplayName("every missing item knows which step fixes it")
    void missingItemsCarryTheirStep() {
        List<ListingDraftService.Missing> missing = drafts.missingForPublication(draft);

        assertThat(missing).isNotEmpty();
        assertThat(missing).allSatisfy(item -> {
            assertThat(item.label()).isNotBlank();
            assertThat(item.step()).isBetween(1, 6);
        });

        assertThat(missing)
                .filteredOn(m -> m.label().equals("the monthly rent"))
                .singleElement()
                .extracting(ListingDraftService.Missing::step)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the preview links each missing item to its step")
    void thePreviewLinksThem() throws Exception {
        String html = mockMvc.perform(get("/list/" + draft.getId() + "/preview")
                        .with(authentication(signedIn())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // As plain text this list named the problem and left somebody to work
        // out which of six screens held it — on the page whose entire job is
        // getting them over the line.
        assertThat(html).contains("href=\"/list/" + draft.getId() + "/2\"");
        assertThat(html).contains("the monthly rent");
    }

    // ---- The header action -------------------------------------------------

    @Test
    @DisplayName("the wizard header offers publishing, not exiting")
    void theHeaderSaysPublish() throws Exception {
        String html = mockMvc.perform(get("/list/" + draft.getId() + "/2")
                        .with(authentication(signedIn())))
                .andReturn().getResponse().getContentAsString();

        // "Save and exit" named the wrong thing twice: each step had already
        // saved itself, so there was nothing to save, and exiting is the one
        // thing nobody comes to this screen to do.
        assertThat(html).contains("Publish listing");
        assertThat(html).doesNotContain("Save and exit");
        assertThat(html).contains("/list/" + draft.getId() + "/preview");
    }
}
