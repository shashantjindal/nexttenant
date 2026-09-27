package com.nexttenant.listing;

/**
 * Who currently holds the deposit — one of the six handover terms the design
 * calls "the things that usually take a week of messages to find out."
 *
 * <p>Mirrors listings_deposit_paid_to_ck.
 */
public enum DepositPaidTo {
    OWNER("Paid to the owner"),
    EXISTING_TENANT("Paid to the existing tenant"),
    AGENCY("Held by an agency");

    private final String label;

    DepositPaidTo(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
