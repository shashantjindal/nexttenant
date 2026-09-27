package com.nexttenant.listing;

/** Flat layout. Mirrors listings_configuration_ck. */
public enum Configuration {
    RK("1 RK"),
    BHK_1("1 BHK"),
    BHK_2("2 BHK"),
    BHK_3("3 BHK"),
    BHK_4_PLUS("4+ BHK");

    private final String label;

    Configuration(String label) {
        this.label = label;
    }

    /** Display text. Kept here so no template has to map codes to words. */
    public String getLabel() {
        return label;
    }
}
