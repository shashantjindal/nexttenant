package com.nexttenant.listing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The listing lifecycle actions on the dashboard: extend, pause, resume, mark
 * rented, take down.
 *
 * <p>Every method loads through {@link #requireOwned}, so ownership is enforced
 * in one place rather than repeated — and therefore eventually forgotten — at
 * each action.
 */
@Service
public class ListingLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ListingLifecycle.class);

    /** "Extend 14 days" on the dashboard. */
    public static final int EXTENSION_DAYS = 14;

    /**
     * How close to expiry the dashboard starts warning.
     *
     * <p>Three days is enough to act on without being nagging. Expiry is the
     * mechanism that keeps the inventory honest: a listing nobody renews is
     * almost always one that has already gone.
     */
    public static final int EXPIRY_WARNING_DAYS = 3;

    private final ListingRepository listings;
    private final Clock clock;

    public ListingLifecycle(ListingRepository listings, Clock clock) {
        this.listings = listings;
        this.clock = clock;
    }

    @Transactional
    public Listing extend(UUID listingId, UUID ownerId) {
        Listing listing = requireOwned(listingId, ownerId);
        // extend() adds to the existing expiry rather than to today, so
        // extending early does not shorten the listing.
        listing.extend(clock.instant(), EXTENSION_DAYS);
        return listings.save(listing);
    }

    @Transactional
    public Listing pause(UUID listingId, UUID ownerId) {
        Listing listing = requireOwned(listingId, ownerId);
        if (listing.getStatus() != ListingStatus.LIVE) {
            throw new IllegalStateException("Only a live listing can be paused");
        }
        listing.pause();
        return listings.save(listing);
    }

    @Transactional
    public Listing resume(UUID listingId, UUID ownerId) {
        Listing listing = requireOwned(listingId, ownerId);
        if (!listing.getStatus().isResumable()) {
            throw new IllegalStateException("This listing cannot be resumed");
        }

        // A listing resumed after expiry would otherwise come back already
        // expired, vanish from search, and look broken.
        Instant now = clock.instant();
        if (listing.getExpiresAt() == null || listing.getExpiresAt().isBefore(now)) {
            listing.extend(now, EXTENSION_DAYS);
        }
        listing.resume();
        return listings.save(listing);
    }

    /** "Mark as rented" — the outcome the product exists to produce. */
    @Transactional
    public Listing markRented(UUID listingId, UUID ownerId) {
        Listing listing = requireOwned(listingId, ownerId);
        listing.markRented(clock.instant());
        log.info("Listing {} marked rented", listingId);
        return listings.save(listing);
    }

    @Transactional
    public Listing takeDown(UUID listingId, UUID ownerId) {
        Listing listing = requireOwned(listingId, ownerId);
        listing.takeDown(clock.instant());
        return listings.save(listing);
    }

    /**
     * Expires listings past their date.
     *
     * <p>Run on a schedule. Expiry could in principle be computed at read time
     * — "live AND expires_at > now" — but making it a real status change means
     * the dashboard can say "expired" rather than silently showing nothing, and
     * the owner gets an accurate picture of why their listing stopped
     * appearing.
     *
     * @return how many were expired
     */
    @Transactional
    public int expireOverdue() {
        Instant now = clock.instant();
        List<Listing> overdue = listings.findByStatusAndExpiresAtBefore(ListingStatus.LIVE, now);

        overdue.forEach(Listing::expire);
        listings.saveAll(overdue);

        if (!overdue.isEmpty()) {
            log.info("Expired {} listings", overdue.size());
        }
        return overdue.size();
    }

    /**
     * Loads a listing, refusing anyone who does not own it.
     *
     * <p>404 rather than 403, for the same reason as everywhere else: a 403
     * confirms the listing exists.
     */
    private Listing requireOwned(UUID listingId, UUID ownerId) {
        return listings.findById(listingId)
                .filter(l -> l.isOwnedBy(ownerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
