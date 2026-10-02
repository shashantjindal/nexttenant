package com.nexttenant.request;

import com.nexttenant.common.RelativeTime;
import com.nexttenant.common.Rupees;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.user.AgeBracket;
import com.nexttenant.user.User;
import com.nexttenant.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the view models for the requests screens.
 *
 * <p>Separate from {@link RequestService} because this reads across listings,
 * users and contact release, while that one changes state. Keeping them apart
 * means the code that mutates and the code that displays are reviewable on
 * their own.
 *
 * <p>Contact details reach these views only through
 * {@link ContactRelease#forViewer}, which returns empty unless the request is
 * CONNECTED and the viewer is party to it. The {@code contact} field on both
 * records is null in every other case, so a template showing a phone number
 * can only do so when the rule was satisfied.
 */
@Service
public class RequestViews {

    /** A request the current user RECEIVED, on one of their listings. */
    public record Received(
            UUID id,
            UUID listingId,
            String listingArea,
            String listingRent,

            /**
             * The room's status, so a request about a room that is gone can
             * say so.
             *
             * <p>A connected request outlives the listing it was about -
             * rightly, since the two people still have each other's numbers -
             * and the card looked identical whether the room was still going
             * or had been taken a fortnight ago.
             */
            com.nexttenant.listing.ListingStatus listingStatus,
            String requesterName,
            String requesterInitial,
            String requesterAge,
            String note,
            String ago,
            RequestStatus status,
            ContactRelease.ReleasedContact contact
    ) {
        public boolean isConnected() {
            return status == RequestStatus.CONNECTED;
        }

        public boolean isPending() {
            return status == RequestStatus.PENDING;
        }

        /**
         * True when the room itself is no longer available.
         *
         * <p>RENTED is the one worth saying out loud: it is the outcome this
         * product exists to produce, and somebody still waiting on a reply
         * deserves to know the room went rather than be left wondering.
         */
        public boolean listingGone() {
            return listingStatus != null
                    && listingStatus != com.nexttenant.listing.ListingStatus.LIVE;
        }

        public String listingGoneLabel() {
            if (listingStatus == com.nexttenant.listing.ListingStatus.RENTED) {
                return "Taken";
            }
            return "No longer listed";
        }
    }

    /** A request the current user SENT. */
    public record Sent(
            UUID id,
            UUID listingId,
            String listingArea,
            String listingRent,

            /**
             * The room's status, so a request about a room that is gone can
             * say so.
             *
             * <p>A connected request outlives the listing it was about -
             * rightly, since the two people still have each other's numbers -
             * and the card looked identical whether the room was still going
             * or had been taken a fortnight ago.
             */
            com.nexttenant.listing.ListingStatus listingStatus,
            String leaverName,
            String leaverInitial,
            String ago,
            RequestStatus status,
            ContactRelease.ReleasedContact contact
    ) {
        public boolean isConnected() {
            return status == RequestStatus.CONNECTED;
        }

        public boolean isPending() {
            return status == RequestStatus.PENDING;
        }

        /**
         * True when the room itself is no longer available.
         *
         * <p>RENTED is the one worth saying out loud: it is the outcome this
         * product exists to produce, and somebody still waiting on a reply
         * deserves to know the room went rather than be left wondering.
         */
        public boolean listingGone() {
            return listingStatus != null
                    && listingStatus != com.nexttenant.listing.ListingStatus.LIVE;
        }

        public String listingGoneLabel() {
            if (listingStatus == com.nexttenant.listing.ListingStatus.RENTED) {
                return "Taken";
            }
            return "No longer listed";
        }
    }

    private final RequestService requests;
    private final ListingRepository listings;
    private final UserRepository users;
    private final ContactRelease contactRelease;
    private final Clock clock;

    public RequestViews(RequestService requests, ListingRepository listings,
                        UserRepository users, ContactRelease contactRelease, Clock clock) {
        this.requests = requests;
        this.listings = listings;
        this.users = users;
        this.contactRelease = contactRelease;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Received> receivedBy(UUID leaverId) {
        List<InterestRequest> raw = requests.receivedBy(leaverId);

        // Batch the lookups rather than querying per row: a busy listing can
        // have fifteen requests a week, and one query each is how a page that
        // felt fine in testing becomes slow in use.
        Map<UUID, Listing> listingsById = loadListings(raw.stream().map(InterestRequest::getListingId).toList());
        Map<UUID, User> usersById = loadUsers(raw.stream().map(InterestRequest::getRequesterUserId).toList());

        List<Received> views = new ArrayList<>(raw.size());
        for (InterestRequest request : raw) {
            Listing listing = listingsById.get(request.getListingId());
            User requester = usersById.get(request.getRequesterUserId());

            views.add(new Received(
                    request.getId(),
                    request.getListingId(),
                    listing == null ? null : listing.getAreaName(),
                    listing == null ? null : Rupees.format(listing.getMonthlyRentInr()),
                    listing == null ? null : listing.getStatus(),
                    requester == null ? null : requester.getFirstName(),
                    initial(requester),
                    ageLabel(requester),
                    request.getNote(),
                    RelativeTime.ago(request.getCreatedAt(), clock.instant()),
                    request.getStatus(),
                    // Empty unless CONNECTED and this viewer is party to it.
                    contactRelease.forViewer(leaverId, request.getId()).orElse(null)));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public List<Sent> sentBy(UUID requesterId) {
        List<InterestRequest> raw = requests.sentBy(requesterId);

        Map<UUID, Listing> listingsById = loadListings(raw.stream().map(InterestRequest::getListingId).toList());
        Map<UUID, User> leaversById = loadUsers(
                listingsById.values().stream().map(Listing::getLeaverUserId).toList());

        List<Sent> views = new ArrayList<>(raw.size());
        for (InterestRequest request : raw) {
            Listing listing = listingsById.get(request.getListingId());
            User leaver = listing == null ? null : leaversById.get(listing.getLeaverUserId());

            views.add(new Sent(
                    request.getId(),
                    request.getListingId(),
                    listing == null ? null : listing.getAreaName(),
                    listing == null ? null : Rupees.format(listing.getMonthlyRentInr()),
                    listing == null ? null : listing.getStatus(),
                    leaver == null ? null : leaver.getFirstName(),
                    initial(leaver),
                    RelativeTime.ago(request.getCreatedAt(), clock.instant()),
                    request.getStatus(),
                    contactRelease.forViewer(requesterId, request.getId()).orElse(null)));
        }
        return views;
    }

    private Map<UUID, Listing> loadListings(List<UUID> ids) {
        Map<UUID, Listing> byId = new HashMap<>();
        listings.findAllById(ids).forEach(l -> byId.put(l.getId(), l));
        return byId;
    }

    private Map<UUID, User> loadUsers(List<UUID> ids) {
        Map<UUID, User> byId = new HashMap<>();
        users.findAllById(ids).forEach(u -> byId.put(u.getId(), u));
        return byId;
    }

    private static String initial(User user) {
        String name = user == null ? null : user.getFirstName();
        return name == null || name.isBlank() ? "?" : name.substring(0, 1).toUpperCase();
    }

    /** "23–27" from the bracket. A range, never a birthdate we never collected. */
    private static String ageLabel(User user) {
        AgeBracket bracket = user == null ? null : user.getAgeBracket();
        if (bracket == null) {
            return null;
        }
        return bracket.name().replace("AGE_", "").replace("_PLUS", "+").replace('_', '–');
    }
}
