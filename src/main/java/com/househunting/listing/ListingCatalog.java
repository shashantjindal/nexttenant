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

    public ListingCatalog(ListingRepository listings) {
        this.listings = listings;
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
                .map(listing -> ListingCardDto.from(listing, null))
                .getContent();
    }

    @Transactional(readOnly = true)
    public long liveCount() {
        return listings.countByStatus(ListingStatus.LIVE);
    }
}
