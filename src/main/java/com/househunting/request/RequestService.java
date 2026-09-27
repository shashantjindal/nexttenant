package com.househunting.request;

import com.househunting.listing.Listing;
import com.househunting.listing.ListingRepository;
import com.househunting.listing.ListingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sending and answering interest requests.
 *
 * <p>Authorisation lives here, expressed as narrowly as the product allows:
 * only the leaver may accept or decline, only the requester may withdraw, and
 * either party may revoke a connection. Each check is a method that throws,
 * rather than a boolean a caller might forget to consult.
 *
 * <p>This class never reads a phone number. Releasing contact is
 * {@link ContactRelease}, and keeping the two apart means the code that changes
 * state and the code that reveals data are separately reviewable.
 */
@Service
public class RequestService {

    private static final Logger log = LoggerFactory.getLogger(RequestService.class);

    /** Long enough to be specific, short enough to stay readable. */
    public static final int MAX_NOTE_LENGTH = 1000;

    private final InterestRequestRepository requests;
    private final ListingRepository listings;
    private final JdbcClient jdbc;
    private final Clock clock;

    /**
     * Announces what happened, so notifications can be sent after the commit.
     *
     * <p>This class stays ignorant of email entirely: it publishes that a
     * thing occurred and does not know or care who listens. Calling a mailer
     * from inside these {@code @Transactional} methods would send messages
     * about transactions that later roll back — see {@link RequestEvent}.
     */
    private final ApplicationEventPublisher events;

    public RequestService(InterestRequestRepository requests, ListingRepository listings,
                          JdbcClient jdbc, Clock clock, ApplicationEventPublisher events) {
        this.requests = requests;
        this.listings = listings;
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Sends a request.
     *
     * @throws RequestNotAllowedException with a message safe to show the person
     */
    @Transactional
    public InterestRequest send(UUID listingId, UUID requesterId, String note) {
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // Only live listings accept requests. A paused or rented listing
        // returning 404 publicly would otherwise still be requestable by
        // anyone holding an old link.
        if (listing.getStatus() != ListingStatus.LIVE) {
            throw new RequestNotAllowedException("This listing is no longer taking requests");
        }

        if (listing.isOwnedBy(requesterId)) {
            throw new RequestNotAllowedException("This is your own listing");
        }

        Optional<InterestRequest> existing =
                requests.findByListingIdAndRequesterUserId(listingId, requesterId);
        if (existing.isPresent()) {
            throw new RequestNotAllowedException(
                    "You have already asked about this room");
        }

        String trimmed = note == null ? null : note.trim();
        if (trimmed != null && trimmed.length() > MAX_NOTE_LENGTH) {
            throw new RequestNotAllowedException("That note is too long");
        }

        try {
            InterestRequest request = requests.saveAndFlush(
                    new InterestRequest(listingId, requesterId, trimmed));
            bumpRequestCount(listingId);
            events.publishEvent(new RequestEvent.Sent(
                    request.getId(), listingId, requesterId, listing.getLeaverUserId()));
            return request;
        } catch (DataIntegrityViolationException e) {
            // Two submits raced. interest_requests_uq let exactly one through,
            // which is the constraint working rather than a failure.
            throw new RequestNotAllowedException("You have already asked about this room");
        }
    }

    /**
     * The leaver accepts. This is the moment contact becomes readable.
     *
     * <p>Note what this method does NOT do: it does not read, return or log a
     * phone number. It changes a status. Whether that status permits reading a
     * number is {@link ContactRelease}'s question, asked separately, every time.
     */
    @Transactional
    public InterestRequest accept(UUID requestId, UUID leaverId) {
        InterestRequest request = requireLeaver(requestId, leaverId);
        request.accept(clock.instant());
        log.info("Request {} connected", requestId);
        InterestRequest saved = requests.save(request);

        events.publishEvent(new RequestEvent.Accepted(
                saved.getId(), saved.getListingId(), saved.getRequesterUserId(), leaverId));
        return saved;
    }

    @Transactional
    public InterestRequest decline(UUID requestId, UUID leaverId) {
        InterestRequest request = requireLeaver(requestId, leaverId);
        request.decline(clock.instant());
        return requests.save(request);
    }

    @Transactional
    public InterestRequest withdraw(UUID requestId, UUID requesterId) {
        InterestRequest request = requests.findById(requestId)
                .filter(r -> r.wasSentBy(requesterId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        request.withdraw(clock.instant());
        return requests.save(request);
    }

    /**
     * Removes access after a connection. Either party may do this.
     *
     * <p>Both sides can end it because either may have reason to: the leaver
     * because the room went to someone else, the requester because the
     * conversation went badly. Requiring the other party's agreement to stop
     * sharing a phone number would be the wrong default.
     */
    @Transactional
    public InterestRequest revoke(UUID requestId, UUID actorId) {
        InterestRequest request = requests.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        boolean isRequester = request.wasSentBy(actorId);
        boolean isLeaver = listings.findById(request.getListingId())
                .map(l -> l.isOwnedBy(actorId))
                .orElse(false);

        if (!isRequester && !isLeaver) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        request.revoke(clock.instant());
        log.info("Request {} revoked", requestId);
        return requests.save(request);
    }

    // ---- Reads ------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InterestRequest> receivedBy(UUID leaverId) {
        return requests.findReceivedBy(leaverId);
    }

    @Transactional(readOnly = true)
    public List<InterestRequest> sentBy(UUID requesterId) {
        return requests.findByRequesterUserIdOrderByCreatedAtDesc(requesterId);
    }

    @Transactional(readOnly = true)
    public long pendingCountFor(UUID leaverId) {
        return requests.countPendingFor(leaverId);
    }

    /** Answers this person has been given and has not looked at. */
    @Transactional(readOnly = true)
    public long answeredUnseenCountFor(UUID requesterId) {
        return requests.countAnsweredUnseenFor(requesterId);
    }

    /**
     * Marks every answer this person has been given as read.
     *
     * <p>Called when they open the requests page — where the answers are
     * shown, so having looked at it is exactly what "seen" means.
     *
     * <p>Note this is invoked from a GET, which usually deserves suspicion:
     * a state-changing GET can be triggered by a prefetch or a link preview,
     * which is why accepting a request is a POST. The difference is what goes
     * wrong. A prefetch that accepts a request connects two strangers; a
     * prefetch that marks a notification read clears a badge slightly early,
     * on a page the person was about to open anyway.
     */
    @Transactional
    public int markAnswersSeen(UUID requesterId) {
        return requests.markAnswersSeenFor(requesterId, clock.instant());
    }

    /** Drives the button state on a listing page: asked already, or not yet. */
    @Transactional(readOnly = true)
    public Optional<InterestRequest> existingRequest(UUID listingId, UUID requesterId) {
        if (requesterId == null) {
            return Optional.empty();
        }
        return requests.findByListingIdAndRequesterUserId(listingId, requesterId);
    }

    // ---- Internals --------------------------------------------------------

    /**
     * Loads a request, refusing anyone who does not own its listing.
     *
     * <p>404 rather than 403: a 403 would confirm the request exists, letting
     * someone probe for activity on listings that are not theirs.
     */
    private InterestRequest requireLeaver(UUID requestId, UUID leaverId) {
        InterestRequest request = requests.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        boolean owns = listings.findById(request.getListingId())
                .map(l -> l.isOwnedBy(leaverId))
                .orElse(false);

        if (!owns) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return request;
    }

    /** Counter lives in the narrow stats row, not on the wide listings row. */
    private void bumpRequestCount(UUID listingId) {
        jdbc.sql("UPDATE listing_stats SET request_count = request_count + 1, "
                        + "updated_at = now() WHERE listing_id = ?")
                .param(listingId)
                .update();
    }

    /** The action was refused for a reason worth showing the person. */
    public static class RequestNotAllowedException extends RuntimeException {
        public RequestNotAllowedException(String message) {
            super(message);
        }
    }
}
