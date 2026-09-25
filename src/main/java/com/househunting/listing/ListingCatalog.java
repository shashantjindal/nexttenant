package com.househunting.listing;

import com.househunting.listing.dto.ListingCardDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side queries over public listings, returning DTOs rather than entities.
 *
 * <p>The projection lives in the listing package because this feature owns its
 * own data and how it is presented. A controller elsewhere composes a page; it
 * does not need to know how listings are fetched, and — more importantly — never
 * holds an entity it could accidentally hand to a template.
 */
@Service
public class ListingCatalog {

    private final ListingRepository listings;
    private final com.househunting.photo.PhotoService photos;

    public ListingCatalog(ListingRepository listings,
                          com.househunting.photo.PhotoService photos) {
        this.listings = listings;
        this.photos = photos;
    }

    /**
     * Most recently published listings, for the "Just listed" row.
     *
     * <p>readOnly = true lets Hibernate skip dirty-checking — it does not need
     * to track changes to objects nobody will modify — and tells the driver the
     * transaction will not write.
     */
    @Transactional(readOnly = true)
    public List<ListingCardDto> recentlyListed(int limit) {
        return listings
                .findByStatusOrderByPublishedAtDesc(ListingStatus.LIVE, PageRequest.of(0, limit))
                .map(listing -> ListingCardDto.from(listing, coverUrl(listing.getId())))
                .getContent();
    }

    /** Thumbnail URL for the cover photo, or null if the listing has none. */
    private String coverUrl(java.util.UUID listingId) {
        return photos.servableFor(listingId).stream()
                .findFirst()
                .map(p -> "/photos/" + p.getId() + "/thumb")
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public long liveCount() {
        return listings.countByStatus(ListingStatus.LIVE);
    }
}
