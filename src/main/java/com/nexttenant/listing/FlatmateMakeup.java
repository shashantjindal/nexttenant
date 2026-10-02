package com.nexttenant.listing;

/**
 * Who already lives in the flat.
 *
 * <p>Deliberately separate from {@link FlatmateGender}, which says who may
 * TAKE the room. A room open to anyone in a flat of three men is a different
 * proposition from the same room in a mixed flat, and it is the first thing
 * people ask once the rent looks right.
 *
 * <p>Only meaningful when the room is open to anyone — "men only" already
 * tells you the flat is men — so it is asked as a follow-up and left null
 * otherwise.
 *
 * <p>Mirrors {@code listings_flatmate_makeup_ck}.
 */
public enum FlatmateMakeup {

    MEN("All men"),
    WOMEN("All women"),
    MIXED("Mixed");

    private final String label;

    FlatmateMakeup(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
