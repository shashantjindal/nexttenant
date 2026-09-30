package com.nexttenant.listing;

/**
 * Where a listing's stored point came from.
 *
 * <p>The published circle is the same either way — it has always meant
 * "somewhere around here". This is about what the stored point is worth to
 * code that needs a real distance, and about the table not lying about itself:
 * a locality centre looks exactly like a pin, so without this nothing could
 * tell them apart.
 */
public enum LocationSource {

    /** The leaver dragged the marker onto their own building. */
    PIN("Pinned by the leaver"),

    /**
     * Derived from {@code areas.centroid} because no pin was given.
     *
     * <p>The middle of the locality, not the building. Good enough to publish
     * and to search by — it is the same centre a searcher's radius is seeded
     * from when they pick an area instead of a point — and not good enough to
     * measure a walking distance with.
     */
    AREA_CENTROID("Centre of the area");

    private final String label;

    LocationSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** True when the point is the actual building rather than a locality. */
    public boolean isExact() {
        return this == PIN;
    }
}
