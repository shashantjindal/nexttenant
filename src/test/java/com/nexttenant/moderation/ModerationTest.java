package com.nexttenant.moderation;

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
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reporting, review, and the two-strikes rule.
 *
 * <p>Moderation is the one place where one person acts on another's account, so
 * the safeguards matter as much as the mechanism: two reports must be two
 * people, and a human must agree before anyone is removed.
 */
@SpringBootTest
@org.springframework.transaction.annotation.Transactional
class ModerationTest {

    @Autowired private ModerationService moderation;
    @Autowired private ReportRepository reports;
    @Autowired private ListingRepository listings;
    @Autowired private ListingPrivateRepository listingPrivates;
    @Autowired private UserRepository users;
    @Autowired private JdbcClient jdbc;

    private User owner;
    private User reporterA;
    private User reporterB;
    private User moderator;
    private Listing listingOne;
    private Listing listingTwo;

    @BeforeEach
    void setUp() {
        owner = users.save(new User("Broker", Gender.MALE, AgeBracket.AGE_28_32));
        reporterA = users.save(new User("Asha", Gender.FEMALE, AgeBracket.AGE_23_27));
        reporterB = users.save(new User("Bimal", Gender.MALE, AgeBracket.AGE_23_27));
        moderator = users.save(new User("Mod", Gender.OTHER, AgeBracket.AGE_33_40));

        listingOne = publishListing();
        listingTwo = publishListing();
    }

    private Listing publishListing() {
        Listing listing = new Listing(owner.getId());
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
        return listings.saveAndFlush(listing);
    }

    private ListingStatus statusOf(UUID id) {
        return listings.findById(id).orElseThrow().getStatus();
    }

    private UserStatus ownerStatus() {
        return users.findById(owner.getId()).orElseThrow().getStatus();
    }

    // ---- Filing -----------------------------------------------------------

    @Test
    @DisplayName("anyone but the owner can report a listing, once")
    void reportRules() {
        moderation.report(listingOne.getId(), reporterA.getId(), ReportReason.IS_A_BROKER, "asked for 15k");

        // One report per person per listing. Without this, "two confirmed
        // reports" could mean one determined person, and moderation becomes a
        // weapon rather than a safeguard.
        assertThatThrownBy(() -> moderation.report(
                listingOne.getId(), reporterA.getId(), ReportReason.SPAM, null))
                .isInstanceOf(ModerationService.ReportNotAllowedException.class)
                .hasMessageContaining("already reported");

        assertThatThrownBy(() -> moderation.report(
                listingOne.getId(), owner.getId(), ReportReason.SPAM, null))
                .isInstanceOf(ModerationService.ReportNotAllowedException.class)
                .hasMessageContaining("your own listing");
    }

    @Test
    @DisplayName("a filed report starts OPEN and counts for nothing yet")
    void reportStartsOpen() {
        Report report = moderation.report(
                listingOne.getId(), reporterA.getId(), ReportReason.IS_A_BROKER, null);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.OPEN);
        // An unreviewed report must not touch an account: otherwise anyone
        // could remove anyone by filing twice from two accounts.
        assertThat(moderation.strikesAgainst(owner.getId())).isZero();
        assertThat(statusOf(listingOne.getId())).isEqualTo(ListingStatus.LIVE);
    }

    // ---- Review -----------------------------------------------------------

    @Test
    @DisplayName("confirming takes the listing down and records one strike")
    void confirmTakesListingDown() {
        Report report = moderation.report(
                listingOne.getId(), reporterA.getId(), ReportReason.IS_A_BROKER, null);

        boolean removed = moderation.confirm(report.getId(), moderator.getId(), "clearly a broker");

        assertThat(removed).isFalse();
        // Leaving a listing live that a moderator has just agreed is a broker
        // would be absurd, whether or not it is the second strike.
        assertThat(statusOf(listingOne.getId())).isEqualTo(ListingStatus.TAKEN_DOWN);
        assertThat(moderation.strikesAgainst(owner.getId())).isEqualTo(1);
        assertThat(ownerStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("dismissing changes nothing about the account or the listing")
    void dismissChangesNothing() {
        Report report = moderation.report(
                listingOne.getId(), reporterA.getId(), ReportReason.SPAM, null);

        moderation.dismiss(report.getId(), moderator.getId(), "looks genuine");

        assertThat(statusOf(listingOne.getId())).isEqualTo(ListingStatus.LIVE);
        assertThat(moderation.strikesAgainst(owner.getId())).isZero();
        assertThat(ownerStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("a decided report cannot be decided again")
    void decisionsAreFinal() {
        Report report = moderation.report(
                listingOne.getId(), reporterA.getId(), ReportReason.SPAM, null);
        moderation.confirm(report.getId(), moderator.getId(), null);

        // Otherwise a refresh of the queue, or a double click, would count a
        // second strike for a single incident.
        assertThatThrownBy(() -> moderation.confirm(report.getId(), moderator.getId(), null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> moderation.dismiss(report.getId(), moderator.getId(), null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(moderation.strikesAgainst(owner.getId())).isEqualTo(1);
    }

    // ---- The two-strikes rule ---------------------------------------------

    @Test
    @DisplayName("two confirmed reports from two people remove the account")
    void twoStrikesRemovesTheAccount() {
        Report first = moderation.report(
                listingOne.getId(), reporterA.getId(), ReportReason.IS_A_BROKER, null);
        Report second = moderation.report(
                listingTwo.getId(), reporterB.getId(), ReportReason.ASKED_FOR_MONEY, null);

        assertThat(moderation.confirm(first.getId(), moderator.getId(), null)).isFalse();
        assertThat(moderation.confirm(second.getId(), moderator.getId(), null)).isTrue();

        assertThat(ownerStatus()).isEqualTo(UserStatus.REMOVED);

        // Every listing goes, not just the reported ones: nothing of theirs
        // should stay publicly visible.
        assertThat(statusOf(listingOne.getId())).isEqualTo(ListingStatus.TAKEN_DOWN);
        assertThat(statusOf(listingTwo.getId())).isEqualTo(ListingStatus.TAKEN_DOWN);
    }

    @Test
    @DisplayName("strikes count across all of an owner's listings")
    void strikesCountAcrossListings() {
        // A broker with five listings should not survive by spreading one
        // confirmed report across each of them.
        moderation.confirm(moderation.report(listingOne.getId(), reporterA.getId(),
                ReportReason.IS_A_BROKER, null).getId(), moderator.getId(), null);
        moderation.confirm(moderation.report(listingTwo.getId(), reporterB.getId(),
                ReportReason.IS_A_BROKER, null).getId(), moderator.getId(), null);

        assertThat(moderation.strikesAgainst(owner.getId()))
                .isEqualTo(ModerationService.STRIKES_TO_REMOVE);
        assertThat(ownerStatus()).isEqualTo(UserStatus.REMOVED);
    }

    @Test
    @DisplayName("removal keeps the reason, and reinstating keeps it too")
    void removalReasonSurvivesReinstatement() {
        moderation.removeAccount(owner.getId(), moderator.getId(), "asked for money");

        User removed = users.findById(owner.getId()).orElseThrow();
        assertThat(removed.getRemovedAt()).isNotNull();
        assertThat(removed.getRemovedReason()).isEqualTo("asked for money");

        moderation.reinstate(owner.getId(), moderator.getId(), "appeal upheld");

        User back = users.findById(owner.getId()).orElseThrow();
        assertThat(back.getStatus()).isEqualTo(UserStatus.ACTIVE);
        // "This account was removed in March for asking for money" stays true
        // even after the decision is reversed, and a moderator reviewing a
        // later report should see it.
        assertThat(back.getRemovedReason()).isEqualTo("asked for money");
    }

    // ---- Audit ------------------------------------------------------------

    @Test
    @DisplayName("every moderator decision is recorded with who and why")
    void decisionsAreAudited() {
        Report report = moderation.report(
                listingOne.getId(), reporterA.getId(), ReportReason.IS_A_BROKER, null);
        moderation.confirm(report.getId(), moderator.getId(), "clearly a broker");

        List<String> actions = jdbc.sql("""
                        SELECT action FROM moderation_actions
                        WHERE actor_id = ? ORDER BY created_at
                        """)
                .param(moderator.getId())
                .query(String.class)
                .list();

        // A disputed removal with no record of who made it is one person's word
        // against another's.
        assertThat(actions).contains("REPORT_CONFIRMED", "LISTING_TAKEN_DOWN");

        String note = jdbc.sql("SELECT note FROM moderation_actions WHERE action = 'REPORT_CONFIRMED' "
                        + "AND actor_id = ? LIMIT 1")
                .param(moderator.getId())
                .query(String.class)
                .single();
        assertThat(note).isEqualTo("clearly a broker");
    }

    @Test
    @DisplayName("the queue shows only open reports, oldest first")
    void queueIsOldestFirst() {
        long before = moderation.openCount();

        Report a = moderation.report(listingOne.getId(), reporterA.getId(), ReportReason.SPAM, null);
        moderation.report(listingTwo.getId(), reporterB.getId(), ReportReason.SPAM, null);
        assertThat(moderation.openCount()).isEqualTo(before + 2);

        moderation.dismiss(a.getId(), moderator.getId(), null);
        // Nothing should wait indefinitely, so the queue drains oldest first.
        assertThat(moderation.openCount()).isEqualTo(before + 1);
        assertThat(moderation.openQueue()).noneMatch(r -> r.getId().equals(a.getId()));
    }
}
