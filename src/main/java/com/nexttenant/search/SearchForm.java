package com.nexttenant.search;

import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.FlatmateGender;
import com.nexttenant.listing.Furnishing;
import com.nexttenant.listing.RoomType;

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
        String nearby,
        String station,
        String rentMin,
        String rentMax,
        String moveInBy,
        String flexibleMoveIn,
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
                .includeNearbyAreas(wantsNearbyAreas())
                .station(blankToNull(station))
                .rentMin(parseInt(rentMin))
                .rentMax(parseInt(rentMax))
                .moveInBy(effectiveMoveInBy())
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
        addIfPresent(parts, "nearby", nearby);
        addIfPresent(parts, "station", station);
        addIfPresent(parts, "rentMin", rentMin);
        addIfPresent(parts, "rentMax", rentMax);
        addIfPresent(parts, "moveInBy", moveInBy);
        addIfPresent(parts, "flexibleMoveIn", flexibleMoveIn);
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

    /**
     * How much later than the stated date a flexible searcher will look.
     *
     * <p>Six weeks. Short enough that the results are still about the move
     * being planned; long enough to catch the notice periods that actually
     * shift a handover — a month's notice given a fortnight late.
     */
    public static final int FLEXIBLE_GRACE_DAYS = 45;

    /**
     * Whether "I'm fine with later move-ins" is ticked.
     *
     * <p>NOT named {@code isFlexibleMoveIn}. That name collides with the record
     * component {@code flexibleMoveIn} as far as property resolution is
     * concerned: SpEL prefers an {@code isX()} boolean getter over the record
     * accessor of the same name, so a template writing
     * {@code ${form.flexibleMoveIn}} silently got the boolean instead of the
     * string — and a hidden field meant to carry the raw parameter through
     * rendered {@code value="true"}. It still worked, which is what makes it
     * the bad kind of bug: harmless today, baffling the day somebody needs the
     * actual submitted value.
     *
     * <p>A distinct name means the two are two things, and each is reached by
     * asking for it.
     */
    /**
     * Whether "also show nearby areas" is ticked.
     *
     * <p>Named for what it asks rather than after the {@code nearby}
     * parameter, for the same reason as {@link #wantsLaterMoveIns()} below: an
     * {@code isNearby()} would shadow the record accessor in property
     * resolution, and a template asking for {@code ${form.nearby}} would
     * silently get the boolean instead of the submitted string.
     */
    public boolean wantsNearbyAreas() {
        return nearby != null && !nearby.isBlank();
    }

    public boolean wantsLaterMoveIns() {
        return flexibleMoveIn != null && !flexibleMoveIn.isBlank();
    }

    /**
     * The date the search actually filters on.
     *
     * <p><b>Why this lives here and not in SearchCriteria.</b> The hard filter
     * is unchanged — still "available on or before some date". The checkbox
     * does not add a new kind of condition; it changes which date that is. So
     * it is a property of how the panel is read, which is this class's whole
     * job, and the search layer stays exactly as it was. Adding a
     * {@code flexible} flag to SearchCriteria would have pushed a presentation
     * choice down into SQL, where the next person to read the query would have
     * to work out what six weeks meant.
     *
     * <p>The trade-off, stated plainly: {@code criteria.moveInBy()} is no
     * longer the date the visitor typed. Anything that wants to show the date
     * back to them must read it from the form — which the panel does, because
     * the panel is rendered from the form.
     */
    public LocalDate effectiveMoveInBy() {
        LocalDate stated = parseDate(moveInBy);
        if (stated == null) {
            return null;
        }
        return wantsLaterMoveIns() ? stated.plusDays(FLEXIBLE_GRACE_DAYS) : stated;
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
