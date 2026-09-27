package com.househunting.request;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InterestRequestRepository extends JpaRepository<InterestRequest, UUID> {

    /**
     * One request per person per listing, enforced by interest_requests_uq.
     * This is the lookup that makes "have I already asked?" answerable.
     */
    Optional<InterestRequest> findByListingIdAndRequesterUserId(UUID listingId, UUID requesterUserId);

    /** "Requests received" — newest first. */
    List<InterestRequest> findByListingIdOrderByCreatedAtDesc(UUID listingId);

    /** "My requests sent". */
    List<InterestRequest> findByRequesterUserIdOrderByCreatedAtDesc(UUID requesterUserId);

    /** Drives the "{n} waiting" badge on the dashboard. */
    long countByListingIdAndStatus(UUID listingId, RequestStatus status);

    /**
     * Every request across all of a user's listings.
     *
     * <p>A join rather than two round trips, because the dashboard shows this
     * on every load and the alternative is one query per listing.
     */
    @Query("""
            SELECT r FROM InterestRequest r
            WHERE r.listingId IN (
                SELECT l.id FROM Listing l WHERE l.leaverUserId = :userId
            )
            ORDER BY r.createdAt DESC
            """)
    List<InterestRequest> findReceivedBy(@Param("userId") UUID userId);

    @Query("""
            SELECT count(r) FROM InterestRequest r
            WHERE r.status = 'PENDING' AND r.listingId IN (
                SELECT l.id FROM Listing l WHERE l.leaverUserId = :userId
            )
            """)
    long countPendingFor(@Param("userId") UUID userId);

    /**
     * Answers the requester has not looked at yet.
     *
     * <p>The mirror of {@link #countPendingFor}: that one counts what a leaver
     * still owes an answer to, this one counts what a requester has been told
     * and not read. Backed by interest_requests_unseen_ix, whose predicate
     * matches these conditions exactly.
     */
    @Query("""
            SELECT count(r) FROM InterestRequest r
            WHERE r.requesterUserId = :userId
              AND r.requesterSeenAt IS NULL
              AND r.status IN ('CONNECTED', 'DECLINED')
            """)
    long countAnsweredUnseenFor(@Param("userId") UUID userId);

    /**
     * Marks every answer this person has been given as read.
     *
     * <p>A bulk UPDATE rather than loading the rows and setting a field on
     * each: this runs on every visit to the requests page, and the loop
     * version would read N rows to write N rows for a value that does not
     * depend on any of them.
     *
     * <p>The {@code requesterSeenAt IS NULL} guard is what keeps it
     * idempotent. Without it, every page load would restamp the timestamp and
     * "when did they first see this?" would always answer "just now".
     */
    @Modifying
    @Query("""
            UPDATE InterestRequest r SET r.requesterSeenAt = :now
            WHERE r.requesterUserId = :userId
              AND r.requesterSeenAt IS NULL
              AND r.status IN ('CONNECTED', 'DECLINED')
            """)
    int markAnswersSeenFor(@Param("userId") UUID userId, @Param("now") Instant now);
}
