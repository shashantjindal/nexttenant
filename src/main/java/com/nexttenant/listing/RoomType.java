package com.nexttenant.listing;

/** What is actually being handed over. Mirrors listings_room_type_ck. */
public enum RoomType {
    PRIVATE_ROOM("Private room"),
    SHARED_ROOM("Shared room"),
    MASTER_ROOM("Master room"),
    ENTIRE_FLAT("Entire flat");

    private final String label;

    RoomType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
