package com.nexttenant.moderation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReportRepository extends JpaRepository<Report, UUID> {

    /** One report per person per listing, matching reports_uq. */
    Optional<Report> findByListingIdAndReporterUserId(UUID listingId, UUID reporterUserId);

    /** The moderation queue: oldest first, so nothing waits indefinitely. */
    List<Report> findByStatusOrderByCreatedAtAsc(ReportStatus status);

    long countByStatus(ReportStatus status);

    /**
     * Confirmed reports across every listing this user owns.
     *
     * <p>The two-strikes count. Across all their listings, not per listing: a
     * broker posting five listings and collecting one confirmed report on each
     * should not be able to stay by spreading the damage.
     */
    @Query("""
            SELECT count(r) FROM Report r
            WHERE r.status = 'CONFIRMED' AND r.listingId IN (
                SELECT l.id FROM Listing l WHERE l.leaverUserId = :userId
            )
            """)
    long countConfirmedAgainstUser(@Param("userId") UUID userId);

    List<Report> findByListingIdOrderByCreatedAtDesc(UUID listingId);
}
