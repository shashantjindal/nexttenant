package com.househunting.listing.dto;

import com.househunting.common.Rupees;
import com.househunting.listing.Listing;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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
        boolean zeroBrokerage,
        String coverPhotoUrl     // null until photos exist
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
    public static ListingCardDto from(Listing listing, String coverPhotoUrl) {
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
                listing.isZeroBrokerage(),
                coverPhotoUrl
        );
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
