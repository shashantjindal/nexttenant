package com.nexttenant.listing.wizard;

import com.nexttenant.area.AreaRepository;
import com.nexttenant.geo.GeoPoints;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingPrivate;
import com.nexttenant.listing.ListingPrivateRepository;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.listing.ListingStatus;
import org.locationtech.jts.geom.Point;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creating and editing a draft listing.
 *
 * <p>Every step saves immediately rather than accumulating answers in the
 * session until a final submit. The design promises "Save and exit" at every
 * step, and per-step persistence means that button needs no implementation —
 * the work is already saved. It also means a dropped connection on step five
 * costs one step, not six minutes.
 *
 * <p>This is the one service in the codebase that legitimately writes
 * {@link ListingPrivate}: the pin the leaver drags is the single point at which
 * an exact location enters the system.
 */
@Service
public class ListingDraftService {

    private static final int LIVE_DAYS = 14;

    private final ListingRepository listings;
    private final ListingPrivateRepository listingPrivates;
    private final Clock clock;

    /* For the location fallback: a named area already knows roughly where it
       is, which is what makes dropping a pin optional. */
    private final AreaRepository areas;

    public ListingDraftService(ListingRepository listings,
                               ListingPrivateRepository listingPrivates,
                               AreaRepository areas,
                               Clock clock) {
        this.listings = listings;
        this.listingPrivates = listingPrivates;
        this.areas = areas;
        this.clock = clock;
    }

    /**
     * The draft this user is working on, creating one if they have none.
     *
     * <p>One draft per person. Someone who abandons a listing and starts again
     * resumes the same row rather than accumulating half-finished listings
     * nobody will ever publish.
     */
    @Transactional
    public Listing currentDraft(UUID userId) {
        return listings.findByLeaverUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(l -> l.getStatus() == ListingStatus.DRAFT)
                .findFirst()
                .orElseGet(() -> {
                    Listing draft = listings.saveAndFlush(new Listing(userId));
                    // The private row is created alongside, so later steps can
                    // set the pin without worrying about whether it exists.
                    listingPrivates.saveAndFlush(new ListingPrivate(draft.getId()));
                    return draft;
                });
    }

    /**
     * Loads a draft, refusing anyone who does not own it.
     *
     * <p>404 rather than 403, and deliberately: a 403 confirms the listing
     * exists, which is a small information leak on a page that is otherwise
     * careful. Every wizard step goes through here — an ownership check that
     * each step has to remember is an ownership check that one step will
     * forget.
     */
    @Transactional(readOnly = true)
    public Listing requireOwnedDraft(UUID listingId, UUID userId) {
        return listings.findById(listingId)
                .filter(l -> l.isOwnedBy(userId))
                .filter(l -> EDITABLE.contains(l.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Which statuses the wizard will open.
     *
     * <p>This used to be DRAFT alone, which is why "Edit" was a dead button on
     * every published listing — the handler answered 404 for the owner's own
     * live flat.
     *
     * <p>RENTED and TAKEN_DOWN are excluded, for different reasons. A rented
     * listing is a finished story, and editing one would mean the numbers
     * somebody agreed to can be rewritten afterwards. A taken-down listing may
     * have been taken down by MODERATION — letting the owner edit and relist it
     * would turn a removal into a speed bump.
     */
    private static final java.util.Set<ListingStatus> EDITABLE = java.util.EnumSet.of(
            ListingStatus.DRAFT, ListingStatus.LIVE,
            ListingStatus.PAUSED, ListingStatus.EXPIRED);

    /**
     * Refuses an edit that would leave a LIVE listing incomplete.
     *
     * <p>{@code listings_live_complete_ck} already forbids this at the database
     * level, and that is the real guarantee. But hitting it is not an option:
     * a constraint violation marks the transaction rollback-only, so it cannot
     * be caught and turned into a message — the request dies at commit time
     * with an error naming neither the field nor the page. (We learned exactly
     * this in the work-email flow.)
     *
     * <p>So the application refuses first, by throwing its own exception before
     * anything is flushed. The transaction rolls back cleanly, the edit is
     * discarded, and the owner is told which field they just emptied.
     *
     * <p>Called after the change has been applied to the in-memory entity and
     * before the save — that is the only point at which "what would this look
     * like if I saved it?" can be answered.
     */
    /**
     * Moves the wizard marker, but only while the listing is still a draft.
     *
     * <p>{@code wizardStep} means "where did you stop", and the dashboard shows
     * it as "You stopped at step 3 of 6" on unfinished listings. Bumping it
     * while somebody edits a published listing would be recording progress
     * through a wizard they are not walking.
     */
    private void advanceIfDraft(Listing listing, int step) {
        if (listing.getStatus() == ListingStatus.DRAFT) {
            listing.advanceWizardTo(step);
        }
    }

    private void refuseIfLiveAndIncomplete(Listing listing) {
        if (listing.getStatus() != ListingStatus.LIVE) {
            return;
        }
        List<Missing> missing = missingForPublication(listing);
        if (!missing.isEmpty()) {
            throw new IncompleteListingException(missing);
        }
    }

    @Transactional(readOnly = true)
    public Optional<ListingPrivate> privateFor(UUID listingId) {
        return listingPrivates.findById(listingId);
    }

    // ---- Step 1: where ----------------------------------------------------

    /**
     * Records the location.
     *
     * <p>The only place an exact coordinate enters the system. It goes to
     * {@link ListingPrivate}; saving it fires the database trigger that
     * recomputes the public jittered point. No Java code performs that
     * calculation, so none can skip it.
     */
    @Transactional
    public void saveLocation(UUID listingId, UUID userId,
                             String areaName, String station, Short walkMinutes,
                             Short floor, Boolean hasLift,
                             Double latitude, Double longitude,
                             String societyName, String fullAddress) {
        Listing draft = requireOwnedDraft(listingId, userId);
        draft.setLocationDetails(areaName, station, walkMinutes, floor, hasLift);
        advanceIfDraft(draft, 2);
        refuseIfLiveAndIncomplete(draft);
        listings.saveAndFlush(draft);

        ListingPrivate priv = listingPrivates.findById(listingId)
                .orElseGet(() -> new ListingPrivate(listingId));

        if (latitude != null && longitude != null) {
            Point pin = GeoPoints.of(latitude, longitude);
            // A rough bounds check, so a mis-dragged pin or a swapped pair is
            // caught here rather than becoming a listing in the Arabian Sea.
            if (!GeoPoints.isWithinMumbai(pin)) {
                throw new IllegalArgumentException("That pin is outside Mumbai");
            }
            priv.placeAt(pin);
        } else if (!priv.hasRealPin()) {
            // No pin given. Fall back to the centre of the area they named.
            //
            // This used to be where listings died. Every other question in the
            // wizard can be answered from memory in a second; this one asks
            // somebody to find their own building on a map and drag a marker
            // onto it, on a phone, and people simply stopped.
            //
            // The area already knows roughly where it is — areas.centroid is
            // the same centre a searcher's radius is seeded from when they
            // pick an area instead of a point. What is good enough to search
            // from is good enough to publish from.
            //
            // Guarded by hasRealPin() so that re-saving step 1 after dropping
            // a pin, without touching the map, cannot quietly replace a real
            // pin with a locality centre.
            areas.findByName(areaName)
                    .map(area -> GeoPoints.of(area.latitude(), area.longitude()))
                    .filter(GeoPoints::isWithinMumbai)
                    .ifPresent(priv::placeAtAreaCentre);
        }
        priv.setSocietyName(societyName);
        priv.setFullAddress(fullAddress);
        listingPrivates.saveAndFlush(priv);
    }

    // ---- Step 2: the flat -------------------------------------------------

    @Transactional
    public void saveFlat(UUID listingId, UUID userId,
                         com.nexttenant.listing.Configuration configuration,
                         com.nexttenant.listing.RoomType roomType,
                         com.nexttenant.listing.HandoverType handoverType,
                         Short existingFlatmates,
                         com.nexttenant.listing.Furnishing furnishing,
                         List<String> amenities, List<String> perks) {
        Listing draft = requireOwnedDraft(listingId, userId);
        draft.setFlatDetails(configuration, roomType, handoverType, existingFlatmates, furnishing);
        draft.setAmenities(amenities);
        draft.setPerks(perks);
        advanceIfDraft(draft, 3);
        refuseIfLiveAndIncomplete(draft);
        listings.save(draft);
    }

    // ---- Step 3: the money ------------------------------------------------

    @Transactional
    public void saveMoney(UUID listingId, UUID userId,
                          Integer rent, Integer deposit, Integer maintenance,
                          boolean maintenanceIncluded, int brokerage, int buyout) {
        Listing draft = requireOwnedDraft(listingId, userId);
        draft.setMoney(rent, deposit, maintenance, maintenanceIncluded, brokerage, buyout);
        advanceIfDraft(draft, 4);
        refuseIfLiveAndIncomplete(draft);
        listings.save(draft);
    }

    // ---- Step 4: handover terms -------------------------------------------

    @Transactional
    public void saveTerms(UUID listingId, UUID userId,
                          Boolean ownerInformed,
                          com.nexttenant.listing.DepositPaidTo depositPaidTo,
                          Boolean registeredAgreement,
                          com.nexttenant.listing.AgreementHandling agreementHandling,
                          Short noticePeriodDays, Boolean bachelorsAllowed) {
        Listing draft = requireOwnedDraft(listingId, userId);
        draft.setHandoverTerms(ownerInformed, depositPaidTo, registeredAgreement,
                agreementHandling, noticePeriodDays, bachelorsAllowed);
        advanceIfDraft(draft, 5);
        refuseIfLiveAndIncomplete(draft);
        listings.save(draft);
    }

    // ---- Step 5: flatmates and rules --------------------------------------

    @Transactional
    public void saveRules(UUID listingId, UUID userId,
                          com.nexttenant.listing.FlatmateGender gender,
                          String professions,
                          Boolean nonVeg, Boolean pets, Boolean smoking, Boolean guests,
                          String note, java.time.LocalDate availableFrom) {
        Listing draft = requireOwnedDraft(listingId, userId);
        draft.setFlatmateRules(gender, professions,
                nonVeg, pets, smoking, guests, note);
        draft.setAvailableFrom(availableFrom);
        advanceIfDraft(draft, 6);
        refuseIfLiveAndIncomplete(draft);
        listings.save(draft);
    }

    // ---- Step 6: publish --------------------------------------------------

    /**
     * Publishes the draft.
     *
     * <p>Completeness is checked here for a friendly message, and again by
     * listings_live_complete_ck in the database, which is the actual guarantee.
     * The application check exists so a missing field produces "add a rent"
     * rather than a constraint violation; the database check exists because the
     * application check can be bypassed by a bug, a script, or a future code
     * path nobody has written yet.
     */
    @Transactional
    public void publish(UUID listingId, UUID userId) {
        Listing draft = requireOwnedDraft(listingId, userId);

        List<Missing> missing = missingForPublication(draft);
        if (!missing.isEmpty()) {
            throw new IncompleteListingException(missing);
        }

        // Already live? The owner came here by editing a published listing and
        // pressing the button at the end of the flow. Every step already saved
        // itself, so there is nothing left to do — and calling publish() would
        // reset published_at and expires_at, quietly moving the listing back to
        // the top of "Newest" and giving it a fresh fortnight. Editing a typo
        // is not republishing.
        if (draft.getStatus() == ListingStatus.LIVE) {
            return;
        }

        draft.publish(clock.instant(), LIVE_DAYS);
        listings.save(draft);
    }

    /** Whether this listing is an edit of something already published. */
    @Transactional(readOnly = true)
    public boolean isPublished(Listing listing) {
        return listing.getStatus() != ListingStatus.DRAFT;
    }

    /** What still needs filling in, phrased for a person rather than a log. */
    @Transactional(readOnly = true)
    /**
     * Something a listing needs before it can go live, and where to fix it.
     *
     * <p>The step is why this is a record rather than a string. "the monthly
     * rent" told somebody what was wrong and left them to work out which of
     * six screens held it — on the one page whose entire job is getting them
     * over the line. Carrying the step turns each line into a link.
     */
    public record Missing(String label, int step) {
    }

    public List<Missing> missingForPublication(Listing draft) {
        List<Missing> missing = new java.util.ArrayList<>();

        if (draft.getAreaName() == null) {
            missing.add(new Missing("the area", 1));
        }
        if (draft.getApproxLocation() == null) {
            // Only reachable now when the area is unknown too — a recognised
            // area supplies a location by itself, see locationFor(). So this
            // means "we have nothing at all to place this flat by".
            missing.add(new Missing("where the flat is", 1));
        }
        if (draft.getConfiguration() == null) {
            missing.add(new Missing("the flat's configuration", 2));
        }
        if (draft.getRoomType() == null) {
            missing.add(new Missing("what kind of room it is", 2));
        }
        if (draft.getFurnishing() == null) {
            missing.add(new Missing("the furnishing", 2));
        }
        if (draft.getMonthlyRentInr() == null) {
            missing.add(new Missing("the monthly rent", 3));
        }
        if (draft.getDepositInr() == null) {
            missing.add(new Missing("the deposit", 3));
        }
        if (draft.getAvailableFrom() == null) {
            missing.add(new Missing("the move-in date", 5));
        }

        return missing;
    }

    /** Raised when publication is attempted before the listing is complete. */
    public static class IncompleteListingException extends RuntimeException {
        private final List<Missing> missing;

        public IncompleteListingException(List<Missing> missing) {
            super("Listing is not ready to publish");
            this.missing = List.copyOf(missing);
        }

        public List<Missing> getMissing() {
            return missing;
        }
    }
}
