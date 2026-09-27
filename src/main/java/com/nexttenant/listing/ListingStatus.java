package com.nexttenant.listing;

/** Lifecycle of a listing. Mirrors listings_status_ck. */
public enum ListingStatus {
    /** Wizard in progress. Invisible to everyone but the owner. */
    DRAFT("Draft"),
    /** Published and searchable. */
    LIVE("Live"),
    /** Temporarily hidden by the owner; can be resumed. */
    PAUSED("Paused"),
    /** "Mark as rented" — the happy ending. */
    RENTED("Rented"),
    /** Passed its expiry date without being extended. */
    EXPIRED("Expired"),
    /** "Take it down" — withdrawn by the owner or by moderation. */
    TAKEN_DOWN("Taken down");

    private final String label;

    ListingStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** Visible in search results. */
    public boolean isPublic() {
        return this == LIVE;
    }

    /** Can be returned to LIVE by the owner without redoing the wizard. */
    public boolean isResumable() {
        return this == PAUSED || this == EXPIRED;
    }
}
