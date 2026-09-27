package com.nexttenant.listing;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public listing data.
 *
 * <p>Nothing here can reach an exact location: that is
 * {@link ListingPrivateRepository}, and using it is a separate decision.
 */
@Repository
public interface ListingRepository extends JpaRepository<Listing, UUID> {

    // ---- Derived queries -------------------------------------------------
    // Spring Data parses the method name into a query. Excellent for plain
    // attribute matching, and it keeps trivial SQL out of the codebase.

    /** The dashboard's "My listing" panel. */
    List<Listing> findByLeaverUserIdOrderByCreatedAtDesc(UUID leaverUserId);

    /** Fetch for public display, returning empty rather than a hidden listing. */
    Optional<Listing> findByIdAndStatus(UUID id, ListingStatus status);

    /** "Just listed" on the landing page. */
    Page<Listing> findByStatusOrderByPublishedAtDesc(ListingStatus status, Pageable pageable);

    /** Drives the expiry sweep. */
    List<Listing> findByStatusAndExpiresAtBefore(ListingStatus status, Instant cutoff);

    long countByStatus(ListingStatus status);

    // ---- Native queries --------------------------------------------------
    // Where derived queries stop. There is no method-name syntax for
    // ST_DWithin, and no naming convention could produce one: the capability
    // lives in PostGIS, not in JPA's type system. Reaching for SQL here is the
    // right call, not a failure of the abstraction.

    /**
     * Live listings within {@code radiusMetres} of a point, nearest first.
     *
     * <p>Note this measures against {@code approx_location} — the published,
     * jittered point. The exact location is never read, so search results carry
     * up to 300m of deliberate error. That is the design, not a defect.
     *
     * <p>ST_DWithin (rather than ST_Distance in a WHERE clause) is what lets
     * the GiST index do its job: it can discard most rows by bounding box
     * before any distance is computed.
     */
    @Query(value = """
            SELECT l.* FROM listings l
            WHERE l.status = 'LIVE'
              AND ST_DWithin(l.approx_location,
                             ST_MakePoint(:longitude, :latitude)::geography,
                             :radiusMetres)
            ORDER BY ST_Distance(l.approx_location,
                                 ST_MakePoint(:longitude, :latitude)::geography)
            """, nativeQuery = true)
    List<Listing> findLiveNear(@Param("latitude") double latitude,
                               @Param("longitude") double longitude,
                               @Param("radiusMetres") double radiusMetres);
}
