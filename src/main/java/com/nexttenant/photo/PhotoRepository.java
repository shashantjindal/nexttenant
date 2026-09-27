package com.nexttenant.photo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    /** In display order; position 0 is the cover. */
    List<Photo> findByListingIdOrderByPosition(UUID listingId);

    /** Only images that have been stripped and re-encoded are safe to serve. */
    List<Photo> findByListingIdAndProcessedAtIsNotNullOrderByPosition(UUID listingId);

    Optional<Photo> findByObjectKey(String objectKey);

    long countByListingId(UUID listingId);

    long countByListingIdAndProcessedAtIsNotNull(UUID listingId);
}
