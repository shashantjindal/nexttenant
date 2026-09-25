package com.househunting.listing;

import com.househunting.listing.dto.ListingCardDto;
import com.househunting.listing.dto.ListingDetailDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * The public listing detail page.
 */
@Controller
public class ListingDetailController {

    /** How far to look for "Similar rooms nearby". */
    private static final int SIMILAR_RADIUS_METRES = 3000;
    private static final int SIMILAR_COUNT = 3;

    private final ListingDetailService details;
    private final ListingRepository listings;

    public ListingDetailController(ListingDetailService details, ListingRepository listings) {
        this.details = details;
        this.listings = listings;
    }

    @GetMapping("/flats/{id}")
    public String detail(@PathVariable String id, Model model) {
        UUID listingId = parseId(id);

        ListingDetailDto listing = details.findPublicListing(listingId)
                // 404 for anything not LIVE — paused, rented, taken down or
                // never published. Distinguishing "does not exist" from "exists
                // but is hidden" would let anyone confirm a listing's status
                // after it stopped being public.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        details.recordView(listingId);

        model.addAttribute("listing", listing);
        model.addAttribute("similar", similarTo(listing));

        return "listing/detail";
    }

    /**
     * "Similar rooms nearby", measured from the APPROXIMATE point.
     *
     * <p>Searching from the jittered location rather than the real one adds up
     * to 300m of error to a "nearby" list, which nobody will notice, and keeps
     * this page on the same footing as every other: the exact location is never
     * read.
     */
    private List<ListingCardDto> similarTo(ListingDetailDto listing) {
        if (listing.approxLat() == 0 && listing.approxLng() == 0) {
            return List.of();
        }
        return listings.findLiveNear(listing.approxLat(), listing.approxLng(), SIMILAR_RADIUS_METRES)
                .stream()
                .filter(other -> !other.getId().equals(listing.id()))
                .limit(SIMILAR_COUNT)
                .map(other -> ListingCardDto.from(other, null))
                .toList();
    }

    /**
     * A malformed id is a 404, not a 500.
     *
     * <p>{@code /flats/haha} is a crawler or a typo, and
     * {@code IllegalArgumentException} escaping from UUID.fromString would be
     * an error page and a stack trace in the log for something entirely
     * ordinary.
     */
    private static UUID parseId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
