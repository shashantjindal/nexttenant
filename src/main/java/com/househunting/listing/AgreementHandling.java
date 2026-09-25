package com.househunting.listing;

/** How the rental agreement transfers. Mirrors listings_agreement_handling_ck. */
public enum AgreementHandling {
    TRANSFER_EXISTING("Existing agreement transfers"),
    FRESH_AGREEMENT("A fresh agreement is signed"),
    NAME_ADDED("Your name is added to the existing one"),
    NO_AGREEMENT("No written agreement");

    private final String label;

    AgreementHandling(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
