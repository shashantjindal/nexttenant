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

    /**
     * Bedrooms in the flat, or null where the layout does not have a count.
     *
     * <p>For schema.org's {@code numberOfRooms}, which asks about the property
     * rather than the room being advertised — a private room in a 2 BHK is 2.
     *
     * <p>1 RK has no bedroom at all: the room IS the flat. Null rather than 0
     * or 1, because both of those are assertions and neither is true, and
     * omitting a property is the one honest option structured data offers.
     * 4+ BHK is 4 — the floor of what it claims, since the alternative is
     * claiming a number nobody recorded.
     */
    public Integer getBedrooms() {
        return switch (this) {
            case RK -> null;
            case BHK_1 -> 1;
            case BHK_2 -> 2;
            case BHK_3 -> 3;
            case BHK_4_PLUS -> 4;
        };
    }
}
