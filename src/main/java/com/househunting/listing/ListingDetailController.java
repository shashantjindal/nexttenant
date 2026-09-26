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
    private final com.househunting.shortlist.ShortlistService shortlists;
    private final com.househunting.photo.PhotoService photos;

    public ListingDetailController(ListingDetailService details, ListingRepository listings,
                                   com.househunting.shortlist.ShortlistService shortlists,
                                   com.househunting.photo.PhotoService photos) {
        this.details = details;
        this.listings = listings;
        this.shortlists = shortlists;
        this.photos = photos;
    }

    /**
     * One image in the gallery.
     *
     * <p>Two URLs, not one: the strip shows thumbnails and the viewer shows the
     * full image, and letting the browser downscale a 1600px JPEG into a 90px
     * thumbnail wastes most of the bytes on a phone — which is where this page
     * is mostly read.
     */
    public record GalleryPhoto(String full, String thumb) {
    }

    @GetMapping("/flats/{id}")
    public String detail(@PathVariable String id,
                         @org.springframework.security.core.annotation.AuthenticationPrincipal
                                 com.househunting.auth.AuthenticatedUser viewer,
                         Model model) {
        UUID listingId = parseId(id);

        ListingDetailDto listing = details.findPublicListing(listingId)
                // 404 for anything not LIVE — paused, rented, taken down or
                // never published. Distinguishing "does not exist" from "exists
                // but is hidden" would let anyone confirm a listing's status
                // after it stopped being public.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        details.recordView(listingId);

        // servableFor() returns only photos whose processing finished, so an
        // image whose EXIF has not been discarded cannot appear here.
        model.addAttribute("photos", photos.servableFor(listingId).stream()
                .map(p -> new GalleryPhoto("/photos/" + p.getId(), "/photos/" + p.getId() + "/thumb"))
                .toList());

        List<ListingCardDto> similar = similarTo(listing);
        model.addAttribute("listing", listing);
        model.addAttribute("similar", similar);
        model.addAttribute("savedIds",
                shortlists.savedAmong(viewer == null ? null : viewer.id(), similar));
        // Null when signed out, so the heart renders as a prompt to log in
        // rather than as a control that silently does nothing.
        model.addAttribute("shortlisted",
                viewer != null && shortlists.isShortlisted(viewer.id(), listingId));
        model.addAttribute("signedIn", viewer != null);

        // Owners see their own listing all the time — from the dashboard, from
        // a shared link, after publishing. Offering them "Request contact" and
        // then refusing with "This is your own listing" is a dead end the page
        // could have avoided showing. Read from the entity rather than the DTO
        // because the DTO carries no owner id, and it should not start to: it
        // is the object handed to a public template.
        model.addAttribute("isOwner", viewer != null
                && listings.findById(listingId)
                        .map(l -> l.isOwnedBy(viewer.id()))
                        .orElse(false));

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
                // Was passing null for the cover, so every "similar room" card
                // rendered without a photo while the identical card on search
                // had one. At most three lookups, after limit() — not thirty,
                // which is why the limit comes first.
                .map(other -> ListingCardDto.from(other, coverThumbUrl(other.getId())))
                .toList();
    }

    private String coverThumbUrl(UUID listingId) {
        return photos.servableFor(listingId).stream()
                .findFirst()
                .map(p -> "/photos/" + p.getId() + "/thumb")
                .orElse(null);
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
