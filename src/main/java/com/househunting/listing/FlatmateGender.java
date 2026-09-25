package com.househunting.listing;

/**
 * Who the room is open to.
 *
 * <p>Deliberately separate from {@code com.househunting.user.Gender}: that
 * describes a person, this describes a preference on a listing, and ANY has no
 * meaning as a person's gender. Reusing one enum for both would force a
 * nonsensical constant into one of them.
 *
 * <p>Mirrors listings_flatmate_gender_ck.
 */
public enum FlatmateGender {
    MALE("Men only"),
    FEMALE("Women only"),
    ANY("Anyone");

    private final String label;

    FlatmateGender(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
