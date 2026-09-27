package com.nexttenant.listing;

/** Mirrors listings_furnishing_ck. */
public enum Furnishing {
    UNFURNISHED("Unfurnished"),
    SEMI_FURNISHED("Semi-furnished"),
    FULLY_FURNISHED("Fully furnished");

    private final String label;

    Furnishing(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
