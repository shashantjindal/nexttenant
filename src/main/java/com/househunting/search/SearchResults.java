package com.househunting.search;

import com.househunting.listing.dto.ListingCardDto;

import java.util.List;

/**
 * A page of results, and how it was obtained.
 *
 * @param widenedToMetres the radius actually used, when it differs from the one asked for
 * @param nextCursor      opaque position for the next page; null when there is none
 */
public record SearchResults(
        List<ListingCardDto> cards,
        long totalCount,
        SearchOutcome outcome,
        Integer widenedToMetres,
        String nextCursor
) {
    public static SearchResults empty() {
        return new SearchResults(List.of(), 0, SearchOutcome.EMPTY, null, null);
    }

    public boolean isEmpty() {
        return cards.isEmpty();
    }

    public boolean hasNextPage() {
        return nextCursor != null;
    }

    /** Drives the "we widened the radius" banner. */
    public boolean wasBroadened() {
        return outcome == SearchOutcome.WIDENED || outcome == SearchOutcome.RELAXED;
    }

    public String widenedToKm() {
        return widenedToMetres == null ? null
                : "%.1f km".formatted(widenedToMetres / 1000.0);
    }
}
