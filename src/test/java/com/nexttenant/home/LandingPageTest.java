package com.nexttenant.home;

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
import com.nexttenant.user.UserPrivate;
import com.nexttenant.user.UserPrivateRepository;
import com.nexttenant.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real landing page and inspects the HTML that would reach a
 * browser.
 *
 * <p>The second test here is the important one, and it is the kind of test
 * worth writing for every public page: it plants a listing whose private data
 * is known, renders the page, and asserts that none of it appears in the
 * output. Architecture prevents the leak; this notices if the architecture is
 * ever quietly undone — by someone adding a field to the DTO, or passing an
 * entity to a template because it was convenient.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LandingPageTest {

    private static final String SECRET_PHONE = "+919000099999";
    private static final String SECRET_ADDRESS = "Flat 901, Nariman Heights, Colaba Causeway";
    private static final String SECRET_SOCIETY = "Nariman Heights";
    private static final double LAT = 19.0596;
    private static final double LNG = 72.8295;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private UserPrivateRepository userPrivates;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;

    private Listing plantListingWithKnownSecrets() {
        User leaver = users.save(new User("Testuser", Gender.FEMALE, AgeBracket.AGE_23_27));
        userPrivates.save(new UserPrivate(
                leaver.getId(), "leaver-" + leaver.getId() + "@invalid.invalid", SECRET_PHONE));

        Listing listing = new Listing(leaver.getId());
        listing.setLocationDetails("Colaba", "Churchgate", (short) 9, (short) 9, true);
        listing.setFlatDetails(Configuration.BHK_2, RoomType.PRIVATE_ROOM,
                HandoverType.ROOM_ONLY, (short) 1, Furnishing.SEMI_FURNISHED);
        listing.setMoney(44000, 88000, null, false, 0, 0);
        listing.setAvailableFrom(LocalDate.now().plusDays(10));
        listings.saveAndFlush(listing);

        ListingPrivate priv = new ListingPrivate(listing.getId());
        priv.placeAt(GeoPoints.of(LAT, LNG));
        priv.setFullAddress(SECRET_ADDRESS);
        priv.setSocietyName(SECRET_SOCIETY);
        listingPrivates.saveAndFlush(priv);

        // Published now, so it sorts to the front of "Just listed".
        listing.publish(Instant.now(), 14);
        listings.saveAndFlush(listing);
        return listing;
    }

    @Test
    @DisplayName("the landing page renders with the hero and live listings")
    void landingPageRenders() throws Exception {
        plantListingWithKnownSecrets();

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Take their place")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Just listed")))
                // The planted listing's PUBLIC data is present.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Colaba")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("₹44,000")));
    }

    @Test
    @DisplayName("no private data reaches the rendered HTML")
    void publicPageLeaksNothingPrivate() throws Exception {
        plantListingWithKnownSecrets();

        MvcResult result = mockMvc.perform(get("/")).andExpect(status().isOk()).andReturn();
        String html = result.getResponse().getContentAsString();

        // The phone number, in any form someone might have formatted it.
        assertThat(html).doesNotContain(SECRET_PHONE);
        assertThat(html).doesNotContain("9000099999");

        // The address and the society name — the latter matters because a
        // society name is itself an address.
        assertThat(html).doesNotContain(SECRET_ADDRESS);
        assertThat(html).doesNotContain(SECRET_SOCIETY);

        // The exact coordinates, which would let anyone locate the building
        // precisely and make the approximate circle pointless.
        assertThat(html).doesNotContain(String.valueOf(LAT));
        assertThat(html).doesNotContain(String.valueOf(LNG));
    }
}
