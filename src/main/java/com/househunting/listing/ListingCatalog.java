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
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public ListingCatalog(ListingRepository listings,
                          com.househunting.photo.PhotoService photos,
                          org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.listings = listings;
        this.photos = photos;
        this.jdbc = jdbc;
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

    /**
     * Brokerage not paid, in rupees, across every connection this site made.
     *
     * <p><b>This replaces a made-up number.</b> The landing page used to show a
     * hardcoded &#8377;48 lakh with a comment saying "placeholder until we have
     * a real figure". A statistic presented to visitors as fact is a claim, and
     * a fabricated claim on a public page is a problem that a comment in the
     * source does not fix.
     *
     * <p><b>How it is defined.</b> One month's rent per flat that actually
     * produced a connection. A month is the conventional brokerage on a Mumbai
     * rental, and a connection is the point at which the two people could have
     * dealt with each other directly instead of through an agent. Deliberately
     * counted per LISTING rather than per request: ten people asking about one
     * room is one tenancy, and one brokerage.
     *
     * <p>It will read &#8377;0 on launch day, and that is the correct answer.
     * A small true number is worth more than a large invented one — and it
     * grows on its own, which the invented one never would.
     */
    @Transactional(readOnly = true)
    public long brokerageSavedInr() {
        Long total = jdbc.sql("""
                        SELECT COALESCE(SUM(l.monthly_rent_inr), 0)
                          FROM listings l
                         WHERE l.monthly_rent_inr IS NOT NULL
                           AND EXISTS (
                               SELECT 1 FROM interest_requests r
                                WHERE r.listing_id = l.id
                                  AND r.status IN ('CONNECTED', 'REVOKED'))
                        """)
                .query(Long.class)
                .single();
        return total == null ? 0L : total;
    }
}
