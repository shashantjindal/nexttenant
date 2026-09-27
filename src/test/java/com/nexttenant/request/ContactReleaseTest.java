package com.nexttenant.request;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The authorisation matrix for contact release.
 *
 * <p>This is the rule the whole product rests on, so it is tested from every
 * angle: each status, each party, and someone who is neither. A gap here is not
 * a bug in a feature — it is the product's central promise failing.
 */
@SpringBootTest
// Rolled back after each test. Without this the phone numbers built from a
// static counter collide on the second run: the counter resets with the JVM,
// the database does not.
@org.springframework.transaction.annotation.Transactional
class ContactReleaseTest {

    /**
     * A per-RUN base, so leftovers from a previous run cannot collide.
     *
     * <p>The counter alone is not enough: it resets with the JVM while the
     * database does not, so a second run would reuse the first run's numbers.
     * Rollback normally prevents leftovers, but a run that fails before the
     * rollback leaves rows behind — and a test suite that only passes on a
     * clean database is a test suite that fails at the worst moment.
     */
    private static final int PHONE_BASE =
            new java.util.Random().nextInt(90_000);

    private static final AtomicInteger PHONE_SEQ = new AtomicInteger(0);

    private static final String LEAVER_PHONE_PREFIX = "+9198111";
    private static final String REQUESTER_PHONE_PREFIX = "+9198222";

    @Autowired private ContactRelease contactRelease;
    @Autowired private RequestService requests;
    @Autowired private InterestRequestRepository requestRepository;
    @Autowired private UserRepository users;
    @Autowired private UserPrivateRepository userPrivates;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;

    private User leaver;
    private User requester;
    private User bystander;
    private Listing listing;
    private String leaverPhone;
    private String requesterPhone;

    @BeforeEach
    void setUp() {
        // +91 then TEN digits. The prefixes contribute five, so five more here.
        int seq = (PHONE_BASE + PHONE_SEQ.incrementAndGet()) % 100000;
        leaverPhone = LEAVER_PHONE_PREFIX + "%05d".formatted(seq);
        requesterPhone = REQUESTER_PHONE_PREFIX + "%05d".formatted(seq);

        leaver = users.save(new User("Priya", Gender.FEMALE, AgeBracket.AGE_23_27));
        userPrivates.saveAndFlush(new UserPrivate(
                leaver.getId(), "leaver-%05d@invalid.invalid".formatted(seq), leaverPhone));

        requester = users.save(new User("Rahul", Gender.MALE, AgeBracket.AGE_28_32));
        userPrivates.saveAndFlush(new UserPrivate(
                requester.getId(), "requester-%05d@invalid.invalid".formatted(seq), requesterPhone));

        bystander = users.save(new User("Nosy", Gender.OTHER, AgeBracket.AGE_23_27));
        userPrivates.saveAndFlush(new UserPrivate(
                bystander.getId(),
                "bystander-%05d@invalid.invalid".formatted(seq),
                "+9198333" + "%05d".formatted(seq)));

        listing = new Listing(leaver.getId());
        listing.setLocationDetails("Bandra West", "Bandra", (short) 8, (short) 7, true);
        listing.setFlatDetails(Configuration.BHK_2, RoomType.PRIVATE_ROOM,
                HandoverType.ROOM_ONLY, (short) 2, Furnishing.SEMI_FURNISHED);
        listing.setMoney(32000, 64000, null, false, 0, 0);
        listing.setAvailableFrom(LocalDate.now().plusDays(30));
        listings.saveAndFlush(listing);

        ListingPrivate priv = new ListingPrivate(listing.getId());
        priv.placeAt(GeoPoints.of(19.0596, 72.8295));
        priv.setFullAddress("Flat 702, 14th Road, Bandra West");
        priv.setSocietyName("Sunbeam Apartments");
        listingPrivates.saveAndFlush(priv);

        listing.publish(Instant.now(), 14);
        listings.saveAndFlush(listing);
    }

    private InterestRequest pendingRequest() {
        return requests.send(listing.getId(), requester.getId(), "Hello");
    }

    private InterestRequest connectedRequest() {
        InterestRequest request = pendingRequest();
        return requests.accept(request.getId(), leaver.getId());
    }

    // ---- Nothing is released before acceptance ----------------------------

    @Test
    @DisplayName("a PENDING request releases nothing, to either party")
    void pendingReleasesNothing() {
        InterestRequest request = pendingRequest();

        assertThat(contactRelease.forViewer(requester.getId(), request.getId())).isEmpty();
        assertThat(contactRelease.forViewer(leaver.getId(), request.getId())).isEmpty();
    }

    @Test
    @DisplayName("a DECLINED request releases nothing, then or ever")
    void declinedReleasesNothing() {
        InterestRequest request = pendingRequest();
        requests.decline(request.getId(), leaver.getId());

        assertThat(contactRelease.forViewer(requester.getId(), request.getId())).isEmpty();
        assertThat(contactRelease.forViewer(leaver.getId(), request.getId())).isEmpty();
    }

    @Test
    @DisplayName("a WITHDRAWN request releases nothing")
    void withdrawnReleasesNothing() {
        InterestRequest request = pendingRequest();
        requests.withdraw(request.getId(), requester.getId());

        assertThat(contactRelease.forViewer(requester.getId(), request.getId())).isEmpty();
        assertThat(contactRelease.forViewer(leaver.getId(), request.getId())).isEmpty();
    }

    // ---- The one case that does release -----------------------------------

    @Test
    @DisplayName("once CONNECTED, each party sees the OTHER's number")
    void connectedReleasesMutually() {
        InterestRequest request = connectedRequest();

        var forRequester = contactRelease.forViewer(requester.getId(), request.getId()).orElseThrow();
        assertThat(forRequester.theirPhone()).isEqualTo(leaverPhone);
        assertThat(forRequester.theirFirstName()).isEqualTo("Priya");
        assertThat(forRequester.theirRole()).isEqualTo("Current tenant");

        var forLeaver = contactRelease.forViewer(leaver.getId(), request.getId()).orElseThrow();
        assertThat(forLeaver.theirPhone()).isEqualTo(requesterPhone);
        assertThat(forLeaver.theirFirstName()).isEqualTo("Rahul");
        assertThat(forLeaver.theirRole()).isEqualTo("Wants the room");
    }

    @Test
    @DisplayName("neither party is handed their own number back")
    void nobodyGetsTheirOwnNumber() {
        InterestRequest request = connectedRequest();

        assertThat(contactRelease.forViewer(requester.getId(), request.getId())
                .orElseThrow().theirPhone()).isNotEqualTo(requesterPhone);
        assertThat(contactRelease.forViewer(leaver.getId(), request.getId())
                .orElseThrow().theirPhone()).isNotEqualTo(leaverPhone);
    }

    @Test
    @DisplayName("the address goes to the requester only")
    void addressGoesToTheRequesterOnly() {
        InterestRequest request = connectedRequest();

        var forRequester = contactRelease.forViewer(requester.getId(), request.getId()).orElseThrow();
        assertThat(forRequester.fullAddress()).isEqualTo("Flat 702, 14th Road, Bandra West");
        assertThat(forRequester.societyName()).isEqualTo("Sunbeam Apartments");

        // The leaver lives there. Sending it back to them would be private data
        // travelling for no reason.
        var forLeaver = contactRelease.forViewer(leaver.getId(), request.getId()).orElseThrow();
        assertThat(forLeaver.fullAddress()).isNull();
        assertThat(forLeaver.societyName()).isNull();
    }

    @Test
    @DisplayName("the WhatsApp link is a plain wa.me deeplink to the other party")
    void whatsappLinkIsADeeplink() {
        InterestRequest request = connectedRequest();
        var forRequester = contactRelease.forViewer(requester.getId(), request.getId()).orElseThrow();

        // No WhatsApp Business API: that costs money per conversation, needs
        // approval, and would put us in the middle of a conversation these two
        // should have directly.
        assertThat(forRequester.whatsappUrl())
                .startsWith("https://wa.me/" + leaverPhone.replace("+", ""))
                // The number segment carries no "+"; wa.me wants bare digits.
                // The query string legitimately does, because URL encoding uses
                // "+" for spaces — which is why this asserts on the prefix
                // rather than on the whole URL.
                .doesNotContain("wa.me/+");
    }

    // ---- Third parties ----------------------------------------------------

    @Test
    @DisplayName("somebody who is party to neither side gets nothing")
    void bystanderGetsNothing() {
        InterestRequest request = connectedRequest();

        // The most important row in this matrix: a connection between two other
        // people must not be readable by a third, however they got the id.
        assertThat(contactRelease.forViewer(bystander.getId(), request.getId())).isEmpty();
    }

    @Test
    @DisplayName("an unknown request id and a null viewer both yield nothing")
    void unknownInputsYieldNothing() {
        assertThat(contactRelease.forViewer(requester.getId(), UUID.randomUUID())).isEmpty();
        assertThat(contactRelease.forViewer(null, UUID.randomUUID())).isEmpty();
        assertThat(contactRelease.forViewer(requester.getId(), null)).isEmpty();
    }

    // ---- Revocation -------------------------------------------------------

    @Test
    @DisplayName("revoking removes access from BOTH parties")
    void revokeRemovesAccessFromBoth() {
        InterestRequest request = connectedRequest();
        assertThat(contactRelease.forViewer(requester.getId(), request.getId())).isPresent();

        requests.revoke(request.getId(), requester.getId());

        assertThat(contactRelease.forViewer(requester.getId(), request.getId())).isEmpty();
        assertThat(contactRelease.forViewer(leaver.getId(), request.getId())).isEmpty();
    }

    @Test
    @DisplayName("revoking keeps the record that a connection happened")
    void revokeDoesNotRewriteHistory() {
        InterestRequest request = connectedRequest();
        requests.revoke(request.getId(), leaver.getId());

        InterestRequest after = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RequestStatus.REVOKED);
        // Contact stops being readable because the status no longer releases
        // it, not because the past was erased. "When did these two connect?" is
        // the first question asked after an incident.
        assertThat(after.getConnectedAt()).isNotNull();
    }

    // ---- Who may do what --------------------------------------------------

    @Test
    @DisplayName("only the leaver can accept or decline")
    void onlyLeaverCanAnswer() {
        InterestRequest request = pendingRequest();

        assertThatThrownBy(() -> requests.accept(request.getId(), requester.getId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> requests.accept(request.getId(), bystander.getId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> requests.decline(request.getId(), bystander.getId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        assertThat(requestRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(RequestStatus.PENDING);
    }

    @Test
    @DisplayName("a declined request cannot later be accepted")
    void terminalStatesAreTerminal() {
        InterestRequest request = pendingRequest();
        requests.decline(request.getId(), leaver.getId());

        // Otherwise a stale form resubmission could resurrect a decision
        // somebody had already been told the answer to.
        assertThatThrownBy(() -> requests.accept(request.getId(), leaver.getId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(contactRelease.forViewer(requester.getId(), request.getId())).isEmpty();
    }

    @Test
    @DisplayName("you cannot request your own listing, or request twice")
    void requestRulesAreEnforced() {
        assertThatThrownBy(() -> requests.send(listing.getId(), leaver.getId(), "hi"))
                .isInstanceOf(RequestService.RequestNotAllowedException.class)
                .hasMessageContaining("your own listing");

        pendingRequest();
        assertThatThrownBy(() -> requests.send(listing.getId(), requester.getId(), "again"))
                .isInstanceOf(RequestService.RequestNotAllowedException.class)
                .hasMessageContaining("already asked");
    }
}
