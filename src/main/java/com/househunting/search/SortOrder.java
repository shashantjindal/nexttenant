package com.househunting.search;

/**
 * The sort options in the design: "Newest", "Rent low to high", "Move-in date".
 *
 * <p>An enum, not a string, because ORDER BY <strong>cannot be
 * parameterised</strong>. Every other filter value is bound as a parameter and
 * therefore cannot be SQL; a sort column has to be interpolated into the
 * statement, so the only safe source is a fixed set we defined. Accepting
 * {@code ?sort=} from a query string and pasting it into ORDER BY is a
 * textbook injection.
 *
 * <p>Each option also names its keyset column, because pagination has to seek
 * on whatever the sort is.
 */
public enum SortOrder {

    NEWEST("Newest", "published_at", false),
    RENT_LOW_TO_HIGH("Rent low to high", "monthly_rent_inr", true),
    MOVE_IN_DATE("Move-in date", "available_from", true);

    private final String label;
    private final String column;
    private final boolean ascending;

    SortOrder(String label, String column, boolean ascending) {
        this.label = label;
        this.column = column;
        this.ascending = ascending;
    }

    public String getLabel() {
        return label;
    }

    /** Safe to interpolate: the value comes from this enum, never from input. */
    public String column() {
        return column;
    }

    public boolean ascending() {
        return ascending;
    }

    public String direction() {
        return ascending ? "ASC" : "DESC";
    }

    /**
     * The SQL type to cast a cursor value back to.
     *
     * <p>A cursor travels through a URL as text, so the seek has to cast it
     * back before comparing. Safe to interpolate for the same reason as
     * { #column()}: it comes from this enum.
     */
    public String castType() {
        return switch (this) {
            case NEWEST -> "timestamptz";
            case RENT_LOW_TO_HIGH -> "integer";
            case MOVE_IN_DATE -> "date";
        };
    }

    /** Strictly greater / strictly less, for the keyset seek. */
    public String keysetComparator() {
        return ascending ? ">" : "<";
    }

    /** Unknown or absent input falls back to the default rather than failing. */
    public static SortOrder fromParam(String value) {
        if (value == null || value.isBlank()) {
            return NEWEST;
        }
        try {
            return SortOrder.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NEWEST;
        }
    }
}
