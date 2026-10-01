package com.nexttenant.listing;

/** What is actually being handed over. Mirrors listings_room_type_ck. */
public enum RoomType {
    PRIVATE_ROOM("Private room"),
    SHARED_ROOM("Shared room"),

    /**
     * A bed in the living room.
     *
     * <p>A real Mumbai arrangement, priced quite differently from a room of
     * your own, and until now it had nowhere to go: somebody listing one had
     * to pick between "shared room" and "private room" and be wrong either
     * way.
     */
    HALL("Hall"),

    /**
     * Kept although the wizard no longer offers it. Listings already carry
     * this value, and an enum that cannot read its own table is worse than
     * one option too many.
     */
    MASTER_ROOM("Master room"),

    ENTIRE_FLAT("Whole flat");

    private final String label;

    RoomType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
