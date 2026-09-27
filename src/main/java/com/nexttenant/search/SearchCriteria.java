package com.nexttenant.search;

import com.nexttenant.listing.Configuration;
import com.nexttenant.listing.FlatmateGender;
import com.nexttenant.listing.Furnishing;
import com.nexttenant.listing.RoomType;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the filter panel can ask for.
 *
 * <p>Every field is optional: the search works with nothing set, and each
 * filter narrows it. A record rather than a dozen method parameters — twelve
 * consecutive nullable arguments is a call nobody can read, and one where
 * swapping two of the same type compiles fine.
 *
 * @param maxResults page size; the cursor decides where the page starts
 */
public record SearchCriteria(
        List<String> areas,

        /*
         * Whether the areas should be widened to their neighbours.
         *
         * It lives beside `areas` because it is not a filter of its own — on
         * its own it does nothing at all. It changes what the area list MEANS,
         * which is why the expansion happens once at the entrance to the
         * search rather than inside the query.
         */
        boolean includeNearbyAreas,
        String station,
        Integer rentMin,
        Integer rentMax,
        LocalDate moveInBy,
        List<Configuration> configurations,
        List<RoomType> roomTypes,
        List<Furnishing> furnishings,
        FlatmateGender flatmateGender,
        List<String> mustHaveAmenities,
        Double latitude,
        Double longitude,
        Integer radiusMetres,
        SortOrder sort,
        SearchCursor cursor,
        int maxResults
) {

    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Used when a point is given without a radius. */
    public static final int DEFAULT_RADIUS_METRES = 2000;
    private static final int MAX_PAGE_SIZE = 60;

    public SearchCriteria {
        // Empty lists rather than nulls, so the query builder never has to ask
        // "null or empty?" — one question instead of two at every filter.
        areas = areas == null ? List.of() : List.copyOf(areas);
        configurations = configurations == null ? List.of() : List.copyOf(configurations);
        roomTypes = roomTypes == null ? List.of() : List.copyOf(roomTypes);
        furnishings = furnishings == null ? List.of() : List.copyOf(furnishings);
        mustHaveAmenities = mustHaveAmenities == null ? List.of() : List.copyOf(mustHaveAmenities);
        sort = sort == null ? SortOrder.NEWEST : sort;

        // Clamped, because page size arrives from a query string and
        // ?size=1000000 is otherwise a free denial-of-service.
        maxResults = maxResults <= 0 ? DEFAULT_PAGE_SIZE : Math.min(maxResults, MAX_PAGE_SIZE);

        // A reversed range matches nothing and looks like a broken search
        // rather than a mistyped filter, so straighten it out.
        if (rentMin != null && rentMax != null && rentMin > rentMax) {
            Integer swap = rentMin;
            rentMin = rentMax;
            rentMax = swap;
        }
    }

    /** Empty criteria: browse everything, newest first. */
    public static SearchCriteria empty() {
        return new SearchCriteria(null, false, null, null, null, null, null, null, null,
                null, null, null, null, null, SortOrder.NEWEST, null, DEFAULT_PAGE_SIZE);
    }

    public boolean hasLocation() {
        return latitude != null && longitude != null && radiusMetres != null;
    }

    /** True if anything at all was narrowed — drives the "Clear all" affordance. */
    public boolean hasAnyFilter() {
        return !areas.isEmpty() || station != null || rentMin != null || rentMax != null
                || moveInBy != null || !configurations.isEmpty() || !roomTypes.isEmpty()
                || !furnishings.isEmpty() || flatmateGender != null
                || !mustHaveAmenities.isEmpty() || hasLocation();
    }

    public int activeFilterCount() {
        int count = 0;
        if (!areas.isEmpty()) count++;
        if (station != null) count++;
        if (rentMin != null || rentMax != null) count++;
        if (moveInBy != null) count++;
        if (!configurations.isEmpty()) count++;
        if (!roomTypes.isEmpty()) count++;
        if (!furnishings.isEmpty()) count++;
        if (flatmateGender != null) count++;
        if (!mustHaveAmenities.isEmpty()) count++;
        return count;
    }

    /** Used by the widen-the-radius fallback. */
    public SearchCriteria withRadius(int newRadiusMetres) {
        return new SearchCriteria(areas, includeNearbyAreas, station, rentMin, rentMax, moveInBy,
                configurations, roomTypes, furnishings, flatmateGender, mustHaveAmenities,
                latitude, longitude, newRadiusMetres, sort, cursor, maxResults);
    }

    /** Used by the last-resort fallback: keep the place, drop the narrowing. */
    public SearchCriteria locationOnly() {
        return new SearchCriteria(null, false, null, null, null, null, null, null, null,
                null, null, latitude, longitude, radiusMetres, sort, null, maxResults);
    }

    /**
     * Replaces the area list, for the sublocality expansion.
     *
     * <p>Ticking "Chembur" has to match listings that name a place inside
     * Chembur, and the listing stores "Diamond Garden". The expansion happens
     * in {@code ListingSearchService}, where a repository is available, rather
     * than in {@code SearchForm}, which is a pure reading of the URL and has no
     * business talking to the database.
     */
    public SearchCriteria withAreas(List<String> newAreas) {
        return new SearchCriteria(newAreas, includeNearbyAreas, station, rentMin, rentMax, moveInBy,
                configurations, roomTypes, furnishings, flatmateGender, mustHaveAmenities,
                latitude, longitude, radiusMetres, sort, cursor, maxResults);
    }

    public SearchCriteria withCursor(SearchCursor newCursor) {
        return new SearchCriteria(areas, includeNearbyAreas, station, rentMin, rentMax, moveInBy,
                configurations, roomTypes, furnishings, flatmateGender, mustHaveAmenities,
                latitude, longitude, radiusMetres, sort, newCursor, maxResults);
    }

    /**
     * A builder, because a sixteen-argument constructor call is unreadable and
     * — worse — silently wrong if two arguments of the same type are swapped.
     * Named setters make each value self-describing at the call site.
     */
    public static final class Builder {
        private List<String> areas;
        private boolean includeNearbyAreas;
        private String station;
        private Integer rentMin;
        private Integer rentMax;
        private LocalDate moveInBy;
        private List<Configuration> configurations;
        private List<RoomType> roomTypes;
        private List<Furnishing> furnishings;
        private FlatmateGender flatmateGender;
        private List<String> mustHaveAmenities;
        private Double latitude;
        private Double longitude;
        private Integer radiusMetres;
        private SortOrder sort = SortOrder.NEWEST;
        private SearchCursor cursor;
        private int maxResults = DEFAULT_PAGE_SIZE;

        public Builder areas(List<String> v) { this.areas = v; return this; }
        public Builder includeNearbyAreas(boolean v) { this.includeNearbyAreas = v; return this; }
        public Builder station(String v) { this.station = v; return this; }
        public Builder rentMin(Integer v) { this.rentMin = v; return this; }
        public Builder rentMax(Integer v) { this.rentMax = v; return this; }
        public Builder moveInBy(LocalDate v) { this.moveInBy = v; return this; }
        public Builder configurations(List<Configuration> v) { this.configurations = v; return this; }
        public Builder roomTypes(List<RoomType> v) { this.roomTypes = v; return this; }
        public Builder furnishings(List<Furnishing> v) { this.furnishings = v; return this; }
        public Builder flatmateGender(FlatmateGender v) { this.flatmateGender = v; return this; }
        public Builder mustHaveAmenities(List<String> v) { this.mustHaveAmenities = v; return this; }
        public Builder sort(SortOrder v) { this.sort = v; return this; }
        public Builder cursor(SearchCursor v) { this.cursor = v; return this; }
        public Builder maxResults(int v) { this.maxResults = v; return this; }

        /**
         * All three together, because a radius without a point is meaningless.
         *
         * <p>Boxed and null-tolerant: these arrive from a URL, where any of
         * them may be absent or unparseable. A partial location is treated as
         * no location rather than as a half-applied filter.
         */
        public Builder location(Double lat, Double lng, Integer radiusMetres) {
            if (lat == null || lng == null) {
                return this;
            }
            this.latitude = lat;
            this.longitude = lng;
            this.radiusMetres = radiusMetres == null ? DEFAULT_RADIUS_METRES : radiusMetres;
            return this;
        }

        public SearchCriteria build() {
            return new SearchCriteria(areas, includeNearbyAreas, station, rentMin, rentMax, moveInBy,
                    configurations, roomTypes, furnishings, flatmateGender, mustHaveAmenities,
                    latitude, longitude, radiusMetres, sort, cursor, maxResults);
        }
    }
}
