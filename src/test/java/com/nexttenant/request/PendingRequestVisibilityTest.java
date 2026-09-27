package com.nexttenant.request;

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

/**
 * Making an unanswered request impossible to miss.
 *
 * <p>The failure this guards against is not a crash. It is a request sitting
 * in an inbox nobody opens until both sides conclude the site is dead — which
 * is how a marketplace of this shape actually dies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PendingRequestVisibilityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;
    @Autowired private RequestService requests;

    private User leaver;
    private User seeker;
    private Listing listing;

    @BeforeEach
    void setUp() {
        leaver = users.save(new User("Priya", Gender.FEMALE, AgeBracket.AGE_23_27));
        seeker = users.save(new User("Rahul", Gender.MALE, AgeBracket.AGE_28_32));

        listing = new Listing(leaver.getId());
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

    // ---- The header badge -------------------------------------------------

    @Test
    @DisplayName("with nothing waiting, the badge says nothing")
    void quietWhenThereIsNothingToAnswer() throws Exception {
        String html = page("/", leaver);

        // The badge element is always in the markup and hidden at zero, so
        // asserting on its presence would prove nothing. The title attribute
        // is the part that changes, and it is what a person actually sees.
        assertThat(html).contains("title=\"Requests\"");
        assertThat(html).doesNotContain("to look at");
    }

    @Test
    @DisplayName("a pending request reaches the header on every page, not just the inbox")
    void badgeAppearsAcrossThePage() throws Exception {
        requests.send(listing.getId(), seeker.getId(), "Is it still free?");

        // The landing page and the search page, because the point of putting
        // this in the layout rather than on one controller is that it follows
        // the person around.
        assertThat(page("/", leaver)).contains("1 to look at");
        assertThat(page("/flats", leaver)).contains("1 to look at");
    }

    @Test
    @DisplayName("the badge counts only what is still unanswered")
    void answeredRequestsStopCounting() throws Exception {
        InterestRequest first = requests.send(listing.getId(), seeker.getId(), null);
        User another = users.save(new User("Sneha", Gender.FEMALE, AgeBracket.AGE_23_27));
        requests.send(listing.getId(), another.getId(), null);

        assertThat(page("/", leaver)).contains("2 to look at");

        requests.accept(first.getId(), leaver.getId());

        // A badge that kept counting answered requests would train people to
        // ignore it, which is worse than not having one.
        assertThat(page("/", leaver)).contains("1 to look at");
    }

    @Test
    @DisplayName("only the person who must answer sees it")
    void theRequesterSeesNoBadge() throws Exception {
        requests.send(listing.getId(), seeker.getId(), null);

        // The seeker SENT the request; there is nothing for them to approve
        // and nothing has been answered yet. Showing them a badge here would
        // be both meaningless and a small leak of somebody else's inbox state.
        //
        // They DO get one the moment they are answered — see
        // RequestNotificationTest — which is the distinction this pins: the
        // badge tracks "something needs you", not "a request exists".
        String html = page("/", seeker);

        assertThat(html).contains("title=\"Requests\"");
        assertThat(html).doesNotContain("to look at");
        assertThat(html).doesNotContain("new reply");
        assertThat(html).doesNotContain("want your room");
    }

    @Test
    @DisplayName("a signed-out visitor gets no badge and no banner")
    void signedOutSeesNeither() throws Exception {
        requests.send(listing.getId(), seeker.getId(), null);

        String html = mockMvc.perform(get("/"))
                .andReturn().getResponse().getContentAsString();

        // HeaderCounts.NONE, and no query run at all for the anonymous case.
        assertThat(html).doesNotContain("to look at");
        assertThat(html).doesNotContain("want your room");
    }

    // ---- The landing-page strip -------------------------------------------

    @Test
    @DisplayName("the landing page says who is waiting, in the singular at one")
    void bannerReadsCorrectlyAtOne() throws Exception {
        requests.send(listing.getId(), seeker.getId(), null);

        String html = page("/", leaver);

        assertThat(html).contains("1 person wants your room");
        assertThat(html).doesNotContain("1 people want");
        // And it says what happens next, because "1 person wants your room"
        // alone does not tell somebody that nothing of theirs has been shared.
        assertThat(html).contains("accept or decline");
    }

    @Test
    @DisplayName("and in the plural above one")
    void bannerReadsCorrectlyAboveOne() throws Exception {
        requests.send(listing.getId(), seeker.getId(), null);
        User another = users.save(new User("Sneha", Gender.FEMALE, AgeBracket.AGE_23_27));
        requests.send(listing.getId(), another.getId(), null);

        assertThat(page("/", leaver)).contains("2 people want your room");
    }

    @Test
    @DisplayName("the strip is absent when there is nothing waiting")
    void bannerIsAbsentWhenQuiet() throws Exception {
        // A permanent strip saying "0 people want your room" would be noise on
        // every visit, and noise is what makes the real one invisible.
        assertThat(page("/", leaver)).doesNotContain("want your room");
        assertThat(page("/", leaver)).doesNotContain("wants your room");
    }

    @Test
    @DisplayName("the strip links to the requests page")
    void bannerLeadsSomewhere() throws Exception {
        requests.send(listing.getId(), seeker.getId(), null);

        String html = page("/", leaver);

        // Telling somebody that people are waiting and not handing them the
        // way to answer would be worse than saying nothing.
        assertThat(html).contains("class=\"waiting\"").contains("href=\"/requests\"");
    }

    // ---- Helpers ----------------------------------------------------------

    private String page(String path, User as) throws Exception {
        return mockMvc.perform(get(path).with(authentication(as(as))))
                .andReturn().getResponse().getContentAsString();
    }

    private static Authentication as(User user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(user.getId(), user.getFirstName(), user.isAdmin()),
                null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
