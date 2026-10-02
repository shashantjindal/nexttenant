package com.nexttenant.listing.dto;

import com.nexttenant.common.Rupees;
import com.nexttenant.listing.Listing;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import com.nexttenant.listing.ListingUrls;

import java.util.UUID;

/**
 * Everything a listing card shows, and nothing else.
 *
 * <p>This is the privacy boundary expressed as a type. Handing the
 * {@link Listing} entity to a template would give that template access to every
 * field on it — including fields added next year by someone who never thought
 * about this page. A DTO inverts the default: the view sees what is declared
 * here, and only that.
 *
 * <p>There is no phone number, no address and no exact location on this record,
 * so a card cannot leak one. Not by convention — the field does not exist, so
 * referencing it fails to compile.
 *
 * <p>Note the strings are pre-formatted. Templates should not be doing currency
 * or date formatting: a Thymeleaf expression that formats money is untestable
 * and gets copied into the next template. Formatting happens here, once, where
 * a unit test can reach it.
 */
public record ListingCardDto(
        UUID id,
        String rent,             // "₹32,000"
        String totalToMoveIn,    // "₹1,13,500"
        String area,             // "Bandra West"
        String roomType,         // "Private room"
        String configuration,    // "2 BHK"
        String station,          // "Bandra"
        String walkTime,         // "8 min walk"
        String availableFrom,    // "1 Nov"
        String furnishing,       // "Semi-furnished"

        /**
         * "Women only" / "Men only", or null when open to anyone.
         *
         * <p>Null rather than "Anyone", deliberately. It is the most common
         * answer, so printing it would put an identical tag on most cards in
         * the results and crowd out the few that differ. A restriction is
         * worth a tag; the absence of one is not.
         */
        String openTo,

        boolean zeroBrokerage,

        /**
         * Thumbnails for the card, in order, cover first.
         *
         * <p>A list rather than a single cover URL because the card opens on a
         * collage and steps through the photos from there. Empty when the
         * listing has none.
         */
        java.util.List<String> photoUrls
) {

    private static final DateTimeFormatter DAY_MONTH =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /**
     * Projects a public {@link Listing} onto a card.
     *
     * <p>Takes the entity rather than loose arguments so the mapping lives in
     * one place; takes only the PUBLIC entity so it has no access to private
     * data even if someone wanted to add a field.
     */
    public static ListingCardDto from(Listing listing, java.util.List<String> photoUrls) {
        return new ListingCardDto(
                listing.getId(),
                Rupees.format(listing.getMonthlyRentInr()),
                Rupees.format(listing.totalMoveInCostInr()),
                listing.getAreaName(),
                listing.getRoomType() == null ? null : listing.getRoomType().getLabel(),
                listing.getConfiguration() == null ? null : listing.getConfiguration().getLabel(),
                listing.getNearestStation(),
                formatWalk(listing.getMinutesToStation()),
                formatAvailability(listing.getAvailableFrom()),
                listing.getFurnishing() == null ? null : listing.getFurnishing().getLabel(),
                openToLabel(listing),
                listing.isZeroBrokerage(),
                photoUrls == null ? java.util.List.of() : java.util.List.copyOf(photoUrls)
        );
    }

    /**
     * Where this listing lives on the site.
     *
     * <p>Built here rather than in each template so every link on the site —
     * cards, dashboard, emails, sitemap — emits the same canonical address.
     * A template writing its own would eventually write the bare id form, and
     * every one of those costs a redirect hop.
     */
    /** The first photo, or null. What the collage leads with. */
    public String coverPhotoUrl() {
        return photoUrls.isEmpty() ? null : photoUrls.getFirst();
    }

    /**
     * Whether the card has enough photos to be worth a collage.
     *
     * <p>Two is the threshold, not three: a collage of one photo beside two
     * empty boxes looks broken, and with two the grid still reads as a set.
     */
    public boolean hasCollage() {
        return photoUrls.size() >= 2;
    }

    /** Up to three, which is what the collage shows. */
    public java.util.List<String> collageUrls() {
        return photoUrls.subList(0, Math.min(3, photoUrls.size()));
    }

    public String path() {
        return ListingUrls.path(id, area, configuration, roomType);
    }

    /**
     * The gender restriction as a tag, or null when there is none.
     *
     * <p>ANY is the common case and says nothing a searcher can act on, so it
     * earns no tag. Only "men only" and "women only" change whether a room is
     * worth opening.
     */
    private static String openToLabel(Listing listing) {
        var gender = listing.getFlatmateGender();
        if (gender == null || gender == com.nexttenant.listing.FlatmateGender.ANY) {
            return null;
        }
        return gender.getLabel();
    }

    private static String formatWalk(Short minutes) {
        return minutes == null ? null : minutes + " min walk";
    }

    private static String formatAvailability(LocalDate from) {
        if (from == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        // "From now" reads better than a past date on a listing that has been
        // live for a while.
        return !from.isAfter(today) ? "Available now" : from.format(DAY_MONTH);
    }
}
