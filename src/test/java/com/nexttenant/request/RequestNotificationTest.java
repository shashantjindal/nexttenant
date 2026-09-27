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
import com.nexttenant.mail.EmailSender;
import com.nexttenant.support.RecordingEmailSender;
import com.nexttenant.user.AgeBracket;
import com.nexttenant.user.Gender;
import com.nexttenant.user.User;
import com.nexttenant.user.UserPrivate;
import com.nexttenant.user.UserPrivateRepository;
import com.nexttenant.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Emails about requests, and the "you have not read this" marker behind the
 * requester's badge.
 *
 * <p><b>Deliberately NOT {@code @Transactional}.</b> The notifier fires on
 * {@code AFTER_COMMIT}, so a test that rolls back would never see a single
 * email — and would pass just as happily if the listener were deleted. Rows
 * are cleaned up by hand instead.
 */
@SpringBootTest(properties = {
        "nexttenant.mail.provider=recording",
        "nexttenant.site.base-url=https://nexttenant.test"
})
@AutoConfigureMockMvc
@Import(RequestNotificationTest.TestBeans.class)
class RequestNotificationTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        EmailSender emailSender() {
            return new RecordingEmailSender();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private EmailSender emailSender;
    @Autowired private UserRepository users;
    @Autowired private UserPrivateRepository userPrivates;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;
    @Autowired private RequestService requests;
    @Autowired private InterestRequestRepository requestRepository;
    @Autowired private JdbcClient jdbc;

    private User leaver;
    private User seeker;
    private Listing listing;

    /**
     * Everything this test created, so it can be removed afterwards.
     *
     * <p>Needed because the class cannot be {@code @Transactional}: the
     * notifier fires on AFTER_COMMIT, and a rolled-back test would never see a
     * single email. Without this, every run would leave rows behind for the
     * next one to trip over.
     */
    private final List<Listing> createdListings = new java.util.ArrayList<>();
    private final List<User> createdUsers = new java.util.ArrayList<>();

    private RecordingEmailSender mail() {
        return (RecordingEmailSender) emailSender;
    }

    @BeforeEach
    void setUp() {
        leaver = newUser("Priya", Gender.FEMALE);
        seeker = newUser("Rahul", Gender.MALE);

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
        createdListings.add(listing);

        mail().clear();
    }

    // ---- The leaver is told somebody asked --------------------------------

    @Test
    @DisplayName("a new request emails the leaver, naming who and where")
    void requestEmailsTheLeaver() {
        requests.send(listing.getId(), seeker.getId(), "Is it free from November?");

        assertThat(mail().count()).isEqualTo(1);
        RecordingEmailSender.Sent sent = mail().last();

        assertThat(sent.to()).isEqualTo(emailFor(leaver));
        // The first name is public — it is on every listing card already — so
        // a subject line can say who without revealing anything.
        assertThat(sent.subject()).contains("Rahul").contains("Bandra West");
        assertThat(sent.body()).contains("Priya").contains("Rahul");
    }

    @Test
    @DisplayName("the email tells them nothing has been shared yet")
    void requestEmailReassures() {
        requests.send(listing.getId(), seeker.getId(), null);

        // Somebody who reads "a stranger asked about your flat" and nothing
        // else has to guess what was handed over. Saying it plainly is what
        // makes opening the email feel safe rather than alarming.
        assertThat(mail().last().body()).contains("Nothing of yours has been shared");
    }

    // ---- The requester is told they were accepted -------------------------

    @Test
    @DisplayName("accepting emails the person who asked")
    void acceptEmailsTheRequester() {
        InterestRequest request = requests.send(listing.getId(), seeker.getId(), null);
        mail().clear();

        requests.accept(request.getId(), leaver.getId());

        assertThat(mail().count()).isEqualTo(1);
        assertThat(mail().last().to()).isEqualTo(emailFor(seeker));
        assertThat(mail().last().subject()).contains("Priya").contains("accepted");
    }

    @Test
    @DisplayName("declining emails nobody")
    void decliningIsSilent() {
        InterestRequest request = requests.send(listing.getId(), seeker.getId(), null);
        mail().clear();

        requests.decline(request.getId(), leaver.getId());

        // A "you were turned down" email is a notification nobody wants in
        // their inbox. The badge and the requests page still show it, so the
        // information is not lost — it is just not pushed at them.
        assertThat(mail().count()).isZero();
    }

    // ---- What the emails must NOT contain ---------------------------------

    @Test
    @DisplayName("no email ever carries a phone number or an address")
    void emailsNeverCarryContactDetails() {
        InterestRequest request = requests.send(listing.getId(), seeker.getId(), null);
        requests.accept(request.getId(), leaver.getId());

        // THE assertion in this class. An email sits in an inbox for years and
        // is entirely outside revoke(): a number sent here keeps working after
        // access is removed, and nothing can be done about it. ContactRelease
        // has to stay the only door, so the emails link to the page instead.
        for (RecordingEmailSender.Sent sent : mail().all()) {
            String all = sent.subject() + "\n" + sent.body();
            assertThat(all).doesNotContain(phoneFor(leaver));
            assertThat(all).doesNotContain(phoneFor(seeker));
            assertThat(all).doesNotContain(emailFor(seeker));
            assertThat(all).doesNotContain("wa.me");
        }
    }

    @Test
    @DisplayName("the link is absolute, because an inbox has no base URL")
    void linksAreAbsolute() {
        requests.send(listing.getId(), seeker.getId(), null);

        // "/requests" in an email is a dead link. This is the whole reason
        // SiteProperties exists.
        assertThat(mail().last().body()).contains("https://nexttenant.test/requests");
    }

    // ---- Delivery must not break the action -------------------------------

    @Test
    @DisplayName("a request still succeeds when there is nobody to email")
    void missingAddressDoesNotBreakTheRequest() {
        // A leaver whose contact row is gone. Contrived, but it stands in for
        // every way delivery can fail — a bounced address, a mail server that
        // times out. The notifier runs after the commit, so a throw there
        // cannot roll anything back; it can only turn a successful action into
        // a 500 on a page where the action already happened.
        User orphan = users.save(new User("Ghost", Gender.OTHER, AgeBracket.AGE_23_27));
        createdUsers.add(orphan);
        Listing theirs = publishedListingFor(orphan);

        InterestRequest request = requests.send(theirs.getId(), seeker.getId(), null);

        assertThat(request.getId()).isNotNull();
        assertThat(requestRepository.findById(request.getId())).isPresent();
        assertThat(mail().count()).isZero();


    }

    // ---- The requester's unread marker ------------------------------------

    @Test
    @DisplayName("an answer is unread until the requester opens the page")
    void answersStayUnreadUntilSeen() throws Exception {
        InterestRequest request = requests.send(listing.getId(), seeker.getId(), null);

        // Nothing to read yet: it has not been answered.
        assertThat(requests.answeredUnseenCountFor(seeker.getId())).isZero();

        requests.accept(request.getId(), leaver.getId());
        assertThat(requests.answeredUnseenCountFor(seeker.getId())).isEqualTo(1);

        mockMvc.perform(get("/requests").with(authentication(as(seeker))));

        // Without this the count would only ever go up — CONNECTED is terminal
        // — and the badge would be lit permanently, which is the same as not
        // having one.
        assertThat(requests.answeredUnseenCountFor(seeker.getId())).isZero();
    }

    @Test
    @DisplayName("the leaver reading their own page does not clear the requester's")
    void seeingIsPerPerson() throws Exception {
        InterestRequest request = requests.send(listing.getId(), seeker.getId(), null);
        requests.accept(request.getId(), leaver.getId());

        mockMvc.perform(get("/requests").with(authentication(as(leaver))));

        // The two sides share a page but not a marker. A bulk update that
        // forgot to filter by requester would clear everybody's.
        assertThat(requests.answeredUnseenCountFor(seeker.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("marking seen is idempotent, so the first-seen time stays true")
    void markingSeenDoesNotRestamp() throws Exception {
        InterestRequest request = requests.send(listing.getId(), seeker.getId(), null);
        requests.accept(request.getId(), leaver.getId());

        mockMvc.perform(get("/requests").with(authentication(as(seeker))));
        Instant first = requestRepository.findById(request.getId())
                .orElseThrow().getRequesterSeenAt();

        mockMvc.perform(get("/requests").with(authentication(as(seeker))));
        Instant second = requestRepository.findById(request.getId())
                .orElseThrow().getRequesterSeenAt();

        // Restamping on every page load would make "when did they first see
        // this?" — the only question the column can answer — always answer
        // "just now".
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("a declined answer counts as news too")
    void declinesAreNewsAsWell() {
        InterestRequest request = requests.send(listing.getId(), seeker.getId(), null);
        requests.decline(request.getId(), leaver.getId());

        // No email is sent for a decline, but the person still needs to find
        // out — otherwise they wait indefinitely on a no, which is worse than
        // the no.
        assertThat(requests.answeredUnseenCountFor(seeker.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the badge counts both sides together")
    void badgeShowsBothSides() throws Exception {
        // Priya is a leaver with someone waiting on her, AND a seeker whose
        // own request was accepted. Anybody moving between flats is both.
        requests.send(listing.getId(), seeker.getId(), null);

        User other = newUser("Sneha", Gender.FEMALE);
        Listing theirs = publishedListingFor(other);
        InterestRequest priyaAsked = requests.send(theirs.getId(), leaver.getId(), null);
        requests.accept(priyaAsked.getId(), other.getId());

        String html = mockMvc.perform(get("/").with(authentication(as(leaver))))
                .andReturn().getResponse().getContentAsString();

        // One badge, both strips.
        assertThat(html).contains("2 to look at");
        assertThat(html).contains("1 person wants your room");
        assertThat(html).contains("You have 1 new reply");


    }

    @Test
    @DisplayName("accepting through the page sends the email and clears the badge")
    void endToEndThroughHttp() throws Exception {
        InterestRequest request = requests.send(listing.getId(), seeker.getId(), null);
        mail().clear();

        mockMvc.perform(post("/requests/" + request.getId() + "/accept")
                .with(authentication(as(leaver))).with(csrf()));

        // The email goes out for a real accept through the controller, which
        // is the path that has a transaction around it.
        assertThat(mail().count()).isEqualTo(1);
        assertThat(mail().last().to()).isEqualTo(emailFor(seeker));

        String home = mockMvc.perform(get("/").with(authentication(as(seeker))))
                .andReturn().getResponse().getContentAsString();
        assertThat(home).contains("You have 1 new reply");
    }

    // ---- Helpers ----------------------------------------------------------

    private User newUser(String name, Gender gender) {
        User created = users.save(new User(name, gender, AgeBracket.AGE_23_27));
        createdUsers.add(created);
        userPrivates.saveAndFlush(new UserPrivate(
                created.getId(), emailFor(created), phoneFor(created)));
        return created;
    }

    private Listing publishedListingFor(User owner) {
        Listing l = new Listing(owner.getId());
        l.setLocationDetails("Powai", "Kanjurmarg", (short) 9, (short) 9, true);
        l.setFlatDetails(Configuration.BHK_2, RoomType.PRIVATE_ROOM,
                HandoverType.ROOM_ONLY, (short) 2, Furnishing.SEMI_FURNISHED);
        l.setMoney(30000, 60000, null, false, 0, 0);
        l.setAvailableFrom(LocalDate.now().plusDays(30));
        listings.saveAndFlush(l);

        ListingPrivate p = new ListingPrivate(l.getId());
        p.placeAt(GeoPoints.of(19.1176, 72.9060));
        listingPrivates.saveAndFlush(p);

        l.publish(Instant.now(), 14);
        Listing saved = listings.saveAndFlush(l);
        createdListings.add(saved);
        return saved;
    }

    /**
     * Removes everything this test made, children before parents.
     *
     * <p>Order matters: a listing cannot go while requests reference it, and a
     * user cannot go while a listing does. Deleting in the wrong order fails
     * on a foreign key — which is the constraint doing its job, in a place
     * that would be irritating to debug.
     */
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        for (Listing l : createdListings) {
            jdbc.sql("DELETE FROM interest_requests WHERE listing_id = ?")
                    .param(l.getId()).update();
            jdbc.sql("DELETE FROM listing_stats WHERE listing_id = ?")
                    .param(l.getId()).update();
            jdbc.sql("DELETE FROM listing_private WHERE listing_id = ?")
                    .param(l.getId()).update();
            jdbc.sql("DELETE FROM listings WHERE id = ?")
                    .param(l.getId()).update();
        }
        for (User u : createdUsers) {
            jdbc.sql("DELETE FROM interest_requests WHERE requester_user_id = ?")
                    .param(u.getId()).update();
            jdbc.sql("DELETE FROM user_private WHERE user_id = ?")
                    .param(u.getId()).update();
            jdbc.sql("DELETE FROM users WHERE id = ?").param(u.getId()).update();
        }
        createdListings.clear();
        createdUsers.clear();
    }

    /** Derived from the id, so parallel rows never collide on the unique index. */
    private static String emailFor(User user) {
        return "user-" + user.getId() + "@invalid.invalid";
    }

    private static String phoneFor(User user) {
        long digits = Math.floorMod(user.getId().getMostSignificantBits(), 1_000_000_000L);
        return "+919" + "%09d".formatted(digits);
    }

    private static Authentication as(User user) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(user.getId(), user.getFirstName(), user.isAdmin()),
                null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
