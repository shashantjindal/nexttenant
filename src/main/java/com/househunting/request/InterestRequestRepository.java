package com.househunting.request;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
