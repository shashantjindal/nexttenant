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

    /**
     * How long a new listing stays up before it has to be renewed.
     *
     * <p>Thirty days, not a fortnight. A fortnight is shorter than a Mumbai
     * flat hunt: notice periods run a month, people line up visits over
     * weekends, and a listing that expires mid-search takes the room out of
     * the results while its owner is still looking for somebody to take it.
     *
     * <p>Deliberately NOT tied to the move-in date. A room available in
     * January listed in October would otherwise sit up for three months
     * without anyone confirming it is still going — and the whole point of
     * expiry is that silence means stale, not that the date has passed.
     */
    private static final int LIVE_DAYS = 30;

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

    // ========================================================================
    // The two-screen flow.
    //
    // Three methods where there were six, and the old ones are gone rather
    // than kept beside these. A save path no screen can reach is a path that
    // drifts from the one that is used, silently, until somebody wires it back
    // up and finds it no longer agrees.
    //
    // Nothing the old screens collected was lost: saveMoreDetail writes all of
    // it, from an optional page offered once a listing is live.
    // ========================================================================

    /**
     * Screen one: the eight things a room cannot be listed without.
     *
     * <p>Also where the flat gets placed. The area is the only location
     * question now — its centroid supplies a point, and its station supplies
     * the station, so two questions disappeared without either answer being
     * lost.
     */
    @Transactional
    public void saveBasics(UUID listingId, UUID userId,
                           String areaName,
                           com.nexttenant.listing.Configuration configuration,
                           com.nexttenant.listing.RoomType roomType,
                           com.nexttenant.listing.Furnishing furnishing,
                           com.nexttenant.listing.FlatType flatType,
                           com.nexttenant.listing.FlatmateGender flatmateGender,
                           java.time.LocalDate availableFrom,
                           Short existingFlatmates) {
        Listing draft = requireOwnedDraft(listingId, userId);

        var area = areas.findByName(areaName);

        draft.setBasics(areaName, configuration, roomType, furnishing, flatType,
                flatmateGender, availableFrom, existingFlatmates);

        // Derived, not asked. Every area knows its own station, and it is the
        // most useful single fact about a Mumbai address — so the question
        // could go without the answer going with it.
        area.ifPresent(a -> draft.setStation(a.nearestStation()));

        advanceIfDraft(draft, 2);
        refuseIfLiveAndIncomplete(draft);
        listings.saveAndFlush(draft);

        placeFromAreaIfUnplaced(listingId, area.orElse(null));
    }

    /**
     * Screen two: money, and the two optional things worth having.
     *
     * <p>The Maps link goes to {@link ListingPrivate}, never to the listing. A
     * Maps link is an address written another way; published, it would make
     * the approximate circle decorative.
     */
    @Transactional
    public void saveMoneyAndExtras(UUID listingId, UUID userId,
                                   Integer rent, Integer deposit,
                                   int brokerage, int buyout,
                                   String mapsLink, String description) {
        Listing draft = requireOwnedDraft(listingId, userId);

        // Maintenance is not asked for on the short flow, so whatever it
        // already holds is preserved rather than being blanked by a screen
        // that never mentioned it.
        draft.setMoney(rent, deposit, draft.getMaintenanceInr(),
                draft.isMaintenanceIncluded(), brokerage, buyout);
        draft.setDescription(trimToNull(description));

        refuseIfLiveAndIncomplete(draft);
        listings.saveAndFlush(draft);

        ListingPrivate priv = listingPrivates.findById(listingId)
                .orElseGet(() -> new ListingPrivate(listingId));
        priv.setMapsLink(trimToNull(mapsLink));
        listingPrivates.saveAndFlush(priv);
    }

    /**
     * Places a flat at its area's centre, if nothing better is known.
     *
     * <p>Extracted so both the short flow and the old location screen go
     * through one rule. {@code hasRealPin()} is what stops a re-save quietly
     * replacing a pin somebody dropped with the middle of their neighbourhood.
     */
    private void placeFromAreaIfUnplaced(UUID listingId, com.nexttenant.area.Area area) {
        if (area == null) {
            return;
        }
        ListingPrivate priv = listingPrivates.findById(listingId)
                .orElseGet(() -> new ListingPrivate(listingId));
        if (priv.hasRealPin()) {
            return;
        }
        Point centre = GeoPoints.of(area.latitude(), area.longitude());
        if (GeoPoints.isWithinMumbai(centre)) {
            priv.placeAtAreaCentre(centre);
            listingPrivates.saveAndFlush(priv);
        }
    }

    /**
     * The optional screen, offered once a listing is live.
     *
     * <p>One method for what used to be four steps. It reads long because the
     * list genuinely is long — and that length is the argument for this screen
     * existing at all rather than sitting in front of publishing.
     *
     * <p>Every argument may be null, and null means "left blank", not "clear
     * it". The page renders what is already stored, so a blank field is a
     * field nobody filled in either time.
     */
    @Transactional
    public void saveMoreDetail(UUID listingId, UUID userId,
                               com.nexttenant.listing.HandoverType handoverType,
                               List<String> amenities, List<String> perks,
                               Integer maintenance, boolean maintenanceIncluded,
                               Short floor, Boolean hasLift,
                               String societyName, String fullAddress,
                               Boolean ownerInformed,
                               com.nexttenant.listing.DepositPaidTo depositPaidTo,
                               Boolean registeredAgreement,
                               com.nexttenant.listing.AgreementHandling agreementHandling,
                               Short noticePeriodDays, Boolean bachelorsAllowed,
                               String flatmateProfessions,
                               Boolean nonVegAllowed, Boolean petsAllowed,
                               Boolean smokingAllowed, Boolean guestsAllowed,
                               String houseRulesNote) {
        Listing draft = requireOwnedDraft(listingId, userId);

        draft.setFlatDetails(draft.getConfiguration(), draft.getRoomType(),
                handoverType, draft.getExistingFlatmates(), draft.getFurnishing());
        draft.setAmenities(amenities);
        draft.setPerks(perks);
        // Area, station and walk time are not on this screen, so they are
        // passed back unchanged. A page that never mentioned a field must not
        // be the page that clears it.
        draft.setLocationDetails(draft.getAreaName(), draft.getNearestStation(),
                draft.getMinutesToStation(), floor, hasLift);

        draft.setMoney(draft.getMonthlyRentInr(), draft.getDepositInr(), maintenance,
                maintenanceIncluded, draft.getBrokerageInr(), draft.getBuyoutInr());

        draft.setHandoverTerms(ownerInformed, depositPaidTo, registeredAgreement,
                agreementHandling, noticePeriodDays, bachelorsAllowed);

        // Gender is answered on screen one and is not on this page either.
        draft.setFlatmateRules(draft.getFlatmateGender(), flatmateProfessions,
                nonVegAllowed, petsAllowed, smokingAllowed, guestsAllowed,
                houseRulesNote);

        refuseIfLiveAndIncomplete(draft);
        listings.saveAndFlush(draft);

        ListingPrivate priv = listingPrivates.findById(listingId)
                .orElseGet(() -> new ListingPrivate(listingId));
        priv.setSocietyName(trimToNull(societyName));
        priv.setFullAddress(trimToNull(fullAddress));
        listingPrivates.saveAndFlush(priv);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---- Publishing --------------------------------------------------------

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
            missing.add(new Missing("the flat's configuration", 1));
        }
        if (draft.getRoomType() == null) {
            missing.add(new Missing("what kind of room it is", 1));
        }
        if (draft.getFurnishing() == null) {
            missing.add(new Missing("the furnishing", 1));
        }
        if (draft.getMonthlyRentInr() == null) {
            missing.add(new Missing("the monthly rent", 2));
        }
        if (draft.getDepositInr() == null) {
            missing.add(new Missing("the deposit", 2));
        }
        if (draft.getAvailableFrom() == null) {
            missing.add(new Missing("the move-in date", 1));
        }
        if (draft.getFlatType() == null) {
            missing.add(new Missing("whether it is a society or a standalone building", 1));
        }
        if (draft.getFlatmateGender() == null) {
            // Required now that it is one tap on screen one. It is also the
            // filter searchers lean on hardest, so a listing without it is
            // one that quietly misses the searches it should appear in.
            missing.add(new Missing("who can take the room", 1));
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
