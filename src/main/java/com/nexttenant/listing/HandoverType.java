package com.nexttenant.listing;

/** Mirrors listings_handover_type_ck. */
public enum HandoverType {
    ROOM_ONLY("Room only"),
    ROOM_WITH_FURNITURE("Room with furniture"),
    ENTIRE_FLAT("Entire flat");

    private final String label;

    HandoverType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
