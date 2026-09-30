package com.nexttenant.listing.dto;

import java.util.List;
import java.util.UUID;

/**
 * Everything the listing detail page shows.
 *
 * <p>This is the page with the largest privacy surface in the product —
 * handover terms, a cost breakdown, the leaver's profile and a map — so it is
 * also where an exact address is most likely to slip in. There is no field for
 * one here. No street address, no society name, no exact coordinate, no phone
 * number: not hidden, absent. A template cannot render what the record does not
 * carry, and adding such a field would be a visible, reviewable change rather
 * than an accident.
 *
 * <p>{@link #approxLat} and {@link #approxLng} are the trigger-maintained
 * jittered point, 120-300m from the truth. The map receives only these.
 *
 * @param costLines the move-in breakdown, in display order
 * @param terms     the six handover terms the design calls "the things that
 *                  usually take a week of messages to find out"
 */
public record ListingDetailDto(
        UUID id,

        // ---- Money ----
        String rent,
        String deposit,
        String maintenance,
        boolean maintenanceIncluded,
        String brokerage,
        boolean zeroBrokerage,
        String buyout,
        String totalToMoveIn,
        List<CostLine> costLines,

        // ---- Where (approximate only) ----
        String area,
        String station,
        String walkTime,
        String floorLabel,
        Boolean hasLift,
        double approxLat,
        double approxLng,
        int approxRadiusMetres,

        // ---- The flat ----
        String configuration,
        String roomType,
        String handoverType,
        String furnishing,
        Integer existingFlatmates,
        List<String> amenities,
        List<String> perks,

        // ---- Handover terms ----
        List<Term> terms,

        // ---- Flatmates and rules ----
        String flatmateGender,
        String flatmateProfessions,
        List<Term> houseRules,

        // ---- Availability ----
        String availableFrom,
        String availableIn,
        String postedAgo,

        // ---- The person leaving (PUBLIC profile only) ----
        String leaverFirstName,
        String leaverMemberSince,
        boolean leaverPhoneVerified,
        boolean leaverWorkEmailVerified,

        // ---- Photos ----
        List<String> photoUrls
) {

    /** One row of the move-in cost breakdown. */
    public record CostLine(String label, String amount, boolean isTotal, boolean isFree) {
    }

    /** A declared fact: "Owner informed — Yes". */
    public record Term(String label, String value, String help) {
    }

    public boolean hasPhotos() {
        return photoUrls != null && !photoUrls.isEmpty();
    }

    public String coverPhotoUrl() {
        return hasPhotos() ? photoUrls.getFirst() : null;
    }

    /** "Approximate locations only" is only honest if the circle is wide enough. */
    public String approxRadiusLabel() {
        return "%.1f km".formatted(approxRadiusMetres / 1000.0);
    }

    /**
     * The canonical address of this listing.
     *
     * <p>Same derivation as {@link com.nexttenant.listing.dto.ListingCardDto},
     * from the same three labels, so the card a visitor clicks and the page
     * they land on agree and no redirect happens in between.
     */
    public String path() {
        return com.nexttenant.listing.ListingUrls.path(id, area, configuration, roomType);
    }
}
