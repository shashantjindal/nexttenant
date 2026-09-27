package com.nexttenant.dashboard;

import com.nexttenant.common.RelativeTime;
import com.nexttenant.common.Rupees;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingLifecycle;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.listing.ListingStatus;
import com.nexttenant.request.RequestService;
import com.nexttenant.shortlist.ShortlistService;
import com.nexttenant.user.User;
import com.nexttenant.user.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Assembles the signed-in dashboard.
 */
@Service
public class DashboardService {

    /** One listing panel, with its counters and available actions. */
    public record MyListing(
            UUID id,
            ListingStatus status,
            String statusLabel,
            String rent,
            String area,
            String roomType,
            String expiryLabel,
            boolean expiringSoon,
            boolean isDraft,
            int wizardStep,
            int views,
            int shortlists,
            int requests
    ) {
        public boolean canPause() {
            return status == ListingStatus.LIVE;
        }

        public boolean canResume() {
            return status.isResumable();
        }

        public boolean canExtend() {
            return status == ListingStatus.LIVE || status.isResumable();
        }

        public boolean canMarkRented() {
            return status == ListingStatus.LIVE || status == ListingStatus.PAUSED;
        }

        public boolean isFinished() {
            return status == ListingStatus.RENTED || status == ListingStatus.TAKEN_DOWN;
        }
    }

    /**
     * How complete a profile is.
     *
     * <p>Shown because the badges visibly change how many replies someone gets,
     * not as a completion-bar for its own sake. Each item names a concrete
     * benefit rather than nagging.
     */
    public record ProfileStrength(int percent, List<String> missing, boolean workEmailVerified) {
        public boolean isComplete() {
            return percent >= 100;
        }
    }

    private final ListingRepository listings;
    private final UserRepository users;
    private final RequestService requests;
    private final ShortlistService shortlists;
    private final JdbcClient jdbc;
    private final Clock clock;

    public DashboardService(ListingRepository listings, UserRepository users,
                            RequestService requests, ShortlistService shortlists,
                            JdbcClient jdbc, Clock clock) {
        this.listings = listings;
        this.users = users;
        this.requests = requests;
        this.shortlists = shortlists;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * The user's listings, newest first.
     *
     * <p>Drafts included: the design shows a half-finished listing so it can be
     * resumed. A draft that disappeared from the dashboard would be a draft
     * nobody ever finishes.
     */
    @Transactional(readOnly = true)
    public List<MyListing> myListings(UUID userId) {
        return listings.findByLeaverUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(l -> l.getStatus() != ListingStatus.TAKEN_DOWN)
                .map(this::toPanel)
                .toList();
    }

    @Transactional(readOnly = true)
    public long pendingRequestCount(UUID userId) {
        return requests.pendingCountFor(userId);
    }

    @Transactional(readOnly = true)
    public long shortlistCount(UUID userId) {
        return shortlists.countFor(userId);
    }

    @Transactional(readOnly = true)
    public ProfileStrength profileStrength(UUID userId) {
        Optional<User> found = users.findById(userId);
        if (found.isEmpty()) {
            return new ProfileStrength(0, List.of(), false);
        }
        User user = found.get();

        // Four things, weighted equally. Phone verification is always present
        // — there is no way to have an account without it — so it counts as
        // earned rather than being listed as missing.
        int score = 25;
        List<String> missing = new java.util.ArrayList<>();

        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            score += 25;
        } else {
            missing.add("Add your first name");
        }

        if (user.getAgeBracket() != null) {
            score += 25;
        } else {
            missing.add("Add your age range — leavers filter on it");
        }

        if (user.isWorkEmailVerified()) {
            score += 25;
        } else {
            missing.add("Verify a work email — the badge people trust most");
        }

        return new ProfileStrength(score, missing, user.isWorkEmailVerified());
    }

    private MyListing toPanel(Listing listing) {
        Stats stats = statsFor(listing.getId());
        Instant now = clock.instant();

        boolean draft = listing.getStatus() == ListingStatus.DRAFT;
        boolean expiringSoon = listing.getStatus() == ListingStatus.LIVE
                && listing.getExpiresAt() != null
                && Duration.between(now, listing.getExpiresAt()).toDays()
                        <= ListingLifecycle.EXPIRY_WARNING_DAYS;

        return new MyListing(
                listing.getId(),
                listing.getStatus(),
                listing.getStatus().getLabel(),
                Rupees.format(listing.getMonthlyRentInr()),
                listing.getAreaName(),
                listing.getRoomType() == null ? null : listing.getRoomType().getLabel(),
                expiryLabel(listing, now),
                expiringSoon,
                draft,
                listing.getWizardStep(),
                stats.views(),
                stats.shortlists(),
                stats.requests());
    }

    private String expiryLabel(Listing listing, Instant now) {
        return switch (listing.getStatus()) {
            case LIVE -> listing.getExpiresAt() == null ? null
                    : "expires " + humanise(Duration.between(now, listing.getExpiresAt()));
            case EXPIRED -> "expired " + RelativeTime.ago(listing.getExpiresAt(), now);
            case RENTED -> "rented " + RelativeTime.ago(listing.getRentedAt(), now);
            case PAUSED -> "paused";
            case DRAFT -> "not published yet";
            case TAKEN_DOWN -> "taken down";
        };
    }

    private static String humanise(Duration remaining) {
        if (remaining.isNegative()) {
            return "today";
        }
        long days = remaining.toDays();
        if (days == 0) {
            return "today";
        }
        return days == 1 ? "tomorrow" : "in " + days + " days";
    }

    private record Stats(int views, int shortlists, int requests) {
    }

    private Stats statsFor(UUID listingId) {
        return jdbc.sql("""
                        -- Aliased to match the record component names. JdbcClient's
                        -- mapper converts snake_case to camelCase
                        -- (nearest_station -> nearestStation), but view_count -> views
                        -- is a different word, not a naming transform.
                        SELECT view_count      AS views,
                               shortlist_count AS shortlists,
                               request_count   AS requests
                        FROM listing_stats WHERE listing_id = ?
                        """)
                .param(listingId)
                .query(Stats.class)
                .optional()
                // A listing always gets a stats row from the trigger in V4, but
                // defaulting costs nothing and stops a missing row becoming a
                // 500 on the dashboard.
                .orElse(new Stats(0, 0, 0));
    }
}
