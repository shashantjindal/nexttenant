package com.househunting.search;

import com.househunting.listing.Configuration;
import com.househunting.listing.FlatmateGender;
import com.househunting.listing.Furnishing;
import com.househunting.listing.RoomType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The filter panel as query parameters.
 *
 * <p>Separate from {@link SearchCriteria} on purpose. This type mirrors the
 * URL — loose strings, anything a person can type or an old bookmark can
 * contain. {@link SearchCriteria} is the validated, typed thing the search
 * layer works with. Keeping them apart means malformed input is handled once,
 * here, at the edge, instead of every layer defending itself.
 *
 * <p>Nothing in here throws. A search URL is shared, truncated, edited and
 * kept for months; {@code ?rentMin=abc} should show results, not a 500. Every
 * unparseable value falls back to "not filtered".
 */
public record SearchForm(
        List<String> area,
        String station,
        String rentMin,
        String rentMax,
        String moveInBy,
        List<String> config,
        List<String> room,
        List<String> furnishing,
        String gender,
        List<String> amenity,
        String lat,
        String lng,
        String radius,
        String sort,
        String cursor
) {

    public SearchForm {
        area = area == null ? List.of() : area;
        config = config == null ? List.of() : config;
        room = room == null ? List.of() : room;
        furnishing = furnishing == null ? List.of() : furnishing;
        amenity = amenity == null ? List.of() : amenity;
    }

    public SearchCriteria toCriteria() {
        return new SearchCriteria.Builder()
                .areas(clean(area))
                .station(blankToNull(station))
                .rentMin(parseInt(rentMin))
                .rentMax(parseInt(rentMax))
                .moveInBy(parseDate(moveInBy))
                .configurations(parseEnums(config, Configuration.class))
                .roomTypes(parseEnums(room, RoomType.class))
                .furnishings(parseEnums(furnishing, Furnishing.class))
                .flatmateGender(parseEnum(gender, FlatmateGender.class))
                .mustHaveAmenities(clean(amenity))
                .sort(SortOrder.fromParam(sort))
                .cursor(SearchCursor.decode(cursor).orElse(null))
                .location(parseDouble(lat), parseDouble(lng), parseInt(radius))
                .build();
    }

    /**
     * Rebuilds the query string without the cursor.
     *
     * <p>Used for the sort control and for "Load more": changing the sort must
     * reset paging, because a cursor is meaningless against a different
     * ordering — keeping it would silently skip or repeat rows.
     */
    public String toQueryStringWithoutCursor() {
        List<String> parts = new ArrayList<>();
        area.forEach(a -> parts.add("area=" + encode(a)));
        addIfPresent(parts, "station", station);
        addIfPresent(parts, "rentMin", rentMin);
        addIfPresent(parts, "rentMax", rentMax);
        addIfPresent(parts, "moveInBy", moveInBy);
        config.forEach(c -> parts.add("config=" + encode(c)));
        room.forEach(r -> parts.add("room=" + encode(r)));
        furnishing.forEach(f -> parts.add("furnishing=" + encode(f)));
        addIfPresent(parts, "gender", gender);
        amenity.forEach(a -> parts.add("amenity=" + encode(a)));
        addIfPresent(parts, "lat", lat);
        addIfPresent(parts, "lng", lng);
        addIfPresent(parts, "radius", radius);
        addIfPresent(parts, "sort", sort);
        return String.join("&", parts);
    }

    /** True if the panel shows anything other than defaults. */
    public boolean hasAnyFilter() {
        return toCriteria().hasAnyFilter();
    }

    /** Helpers for the template: "is this checkbox ticked?" */
    public boolean hasArea(String value) {
        return area.contains(value);
    }

    public boolean hasConfig(String value) {
        return config.contains(value);
    }

    public boolean hasRoom(String value) {
        return room.contains(value);
    }

    public boolean hasFurnishing(String value) {
        return furnishing.contains(value);
    }

    public boolean hasAmenity(String value) {
        return amenity.contains(value);
    }

    // ---- Lenient parsing --------------------------------------------------

    private static List<String> clean(List<String> values) {
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            // Strip separators so "20,000" and "₹20000" both work: people
            // paste what they see.
            return Integer.valueOf(value.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> List<E> parseEnums(List<String> values, Class<E> type) {
        return values.stream()
                .map(v -> parseEnum(v, type))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // An old bookmark may name a value we have since removed. Ignoring
            // it shows more results than intended, which beats an error page.
            return null;
        }
    }

    private static void addIfPresent(List<String> parts, String name, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(name + "=" + encode(value));
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Optional wrapper so templates can ask without null checks. */
    public Optional<String> sortOrDefault() {
        return Optional.ofNullable(blankToNull(sort));
    }
}
