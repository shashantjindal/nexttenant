package com.househunting.request;

import com.househunting.listing.Listing;
import com.househunting.listing.ListingPrivate;
import com.househunting.listing.ListingPrivateRepository;
import com.househunting.listing.ListingRepository;
import com.househunting.user.User;
import com.househunting.user.UserPrivate;
import com.househunting.user.UserPrivateRepository;
import com.househunting.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The single place in this application where one person's contact details are
 * released to another.
 *
 * <p>Everything built so far leads here. The public/private table split, the
 * DTOs without phone fields, the location jitter, the EXIF stripping — all of
 * it exists so that contact information is released in exactly one way, under
 * exactly one condition, through exactly one method.
 *
 * <p><strong>Why one choke point.</strong> Release is conditional, and
 * conditional logic spread across controllers is how a condition gets checked
 * in four places and forgotten in the fifth. Here the rule is stated once:
 *
 * <blockquote>
 * A viewer may read the other party's contact details if and only if an
 * {@link InterestRequest} exists between them, for this listing, with status
 * CONNECTED — and the viewer is one of the two parties to it.
 * </blockquote>
 *
 * <p>No other class is wired to {@link UserPrivateRepository} for someone
 * else's row. A caller cannot bypass this by being careless, because a caller
 * has nothing else to call.
 *
 * <p><strong>Why it takes the viewer as a parameter.</strong> A method like
 * {@code getContactFor(requestId)} would return the details to whoever asked,
 * leaving authorisation to the caller. Taking the viewer makes the question
 * "who is asking?" impossible to omit: there is no way to invoke this without
 * saying who you are.
 */
@Service
public class ContactRelease {

    private static final Logger log = LoggerFactory.getLogger(ContactRelease.class);

    private final InterestRequestRepository requests;
    private final ListingRepository listings;
    private final ListingPrivateRepository listingPrivates;
    private final UserRepository users;
    private final UserPrivateRepository userPrivates;

    public ContactRelease(InterestRequestRepository requests,
                          ListingRepository listings,
                          ListingPrivateRepository listingPrivates,
                          UserRepository users,
                          UserPrivateRepository userPrivates) {
        this.requests = requests;
        this.listings = listings;
        this.listingPrivates = listingPrivates;
        this.users = users;
        this.userPrivates = userPrivates;
    }

    /**
     * What one party may see about the other, once connected.
     *
     * @param theirRole       "Current tenant" or "Wants the room"
     * @param whatsappUrl     a wa.me deeplink; no WhatsApp Business API needed
     * @param fullAddress     released to the requester only; null for the leaver,
     *                        who already knows their own address
     */
    public record ReleasedContact(
            UUID otherUserId,
            String theirFirstName,
            String theirRole,
            String theirPhone,
            String whatsappUrl,
            Instant connectedAt,
            String fullAddress,
            String societyName
    ) {
    }

    /**
     * Releases contact details, or returns empty.
     *
     * <p>Empty is returned for every failure mode — no such request, not
     * connected, viewer is not a party to it — and deliberately without
     * distinguishing them to the caller. A caller that could tell "declined"
     * from "does not exist" could probe for the existence of requests between
     * other people.
     */
    @Transactional(readOnly = true)
    public Optional<ReleasedContact> forViewer(UUID viewerId, UUID requestId) {
        if (viewerId == null || requestId == null) {
            return Optional.empty();
        }

        InterestRequest request = requests.findById(requestId).orElse(null);
        if (request == null) {
            return Optional.empty();
        }

        // THE rule. Everything else in this method is plumbing.
        if (!request.releasesContact()) {
            return Optional.empty();
        }

        Listing listing = listings.findById(request.getListingId()).orElse(null);
        if (listing == null) {
            return Optional.empty();
        }

        boolean viewerIsRequester = request.wasSentBy(viewerId);
        boolean viewerIsLeaver = listing.isOwnedBy(viewerId);

        if (!viewerIsRequester && !viewerIsLeaver) {
            // Somebody who is party to neither side asked for a connection
            // between two other people. Worth a log line: it is not something
            // that happens by accident.
            log.warn("Contact release refused: viewer {} is not party to request {}",
                    viewerId, requestId);
            return Optional.empty();
        }

        UUID otherUserId = viewerIsRequester ? listing.getLeaverUserId() : request.getRequesterUserId();

        User other = users.findById(otherUserId).orElse(null);
        UserPrivate otherPrivate = userPrivates.findById(otherUserId).orElse(null);
        if (other == null || otherPrivate == null) {
            return Optional.empty();
        }

        // The address goes to the requester only. The leaver knows where they
        // live; sending it back to them would be private data travelling for
        // no reason.
        String fullAddress = null;
        String societyName = null;
        if (viewerIsRequester) {
            ListingPrivate priv = listingPrivates.findById(listing.getId()).orElse(null);
            if (priv != null) {
                fullAddress = priv.getFullAddress();
                societyName = priv.getSocietyName();
            }
        }

        return Optional.of(new ReleasedContact(
                otherUserId,
                other.getFirstName(),
                viewerIsRequester ? "Current tenant" : "Wants the room",
                otherPrivate.getPhoneE164(),
                whatsappLink(otherPrivate.getPhoneE164(), listing, viewerIsRequester),
                request.getConnectedAt(),
                fullAddress,
                societyName));
    }

    /**
     * A wa.me deeplink.
     *
     * <p>Deliberately not the WhatsApp Business API: that costs money per
     * conversation, requires approval, and would put us in the middle of a
     * conversation these two people should be having directly. A link opens
     * their own WhatsApp with the number filled in, and we are out of it.
     */
    private String whatsappLink(String phoneE164, Listing listing, boolean viewerIsRequester) {
        // wa.me wants digits only; the leading + is dropped.
        String digits = phoneE164.replaceAll("[^0-9]", "");

        String message = viewerIsRequester
                ? "Hi! I saw your %s in %s on HouseHunting and you accepted my request."
                        .formatted(safe(listing.getRoomType()), safe(listing.getAreaName()))
                : "Hi! You asked about my %s in %s on HouseHunting."
                        .formatted(safe(listing.getRoomType()), safe(listing.getAreaName()));

        return "https://wa.me/%s?text=%s".formatted(
                digits, URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    private static String safe(Object value) {
        if (value == null) {
            return "room";
        }
        return value instanceof com.househunting.listing.RoomType roomType
                ? roomType.getLabel().toLowerCase(java.util.Locale.ENGLISH)
                : value.toString();
    }
}
