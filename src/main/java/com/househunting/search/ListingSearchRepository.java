package com.househunting.search;

import com.househunting.listing.Configuration;
import com.househunting.listing.Furnishing;
import com.househunting.listing.RoomType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and runs the listing search.
 *
 * <p>The query's shape changes per request, because twelve filters are each
 * optional. That is assembled here rather than written as one enormous
 * statement full of {@code (:x IS NULL OR column = :x)}, which defeats the
 * planner: a condition referencing a parameter that is usually null still has
 * to be evaluated, and the planner cannot use an index for a branch that might
 * not apply.
 *
 * <p><strong>On injection.</strong> The dangerous pattern is concatenating a
 * VALUE into SQL. Nothing here does: every value is bound as a named parameter,
 * and the only text concatenated is fixed fragments written in this file. The
 * single exception is ORDER BY, which cannot be parameterised at all — so its
 * column comes from {@link SortOrder}, an enum, and never from input.
 *
 * <p>The search runs entirely against {@code listings.approx_location}. The
 * exact location is never read, so results carry up to 300m of deliberate
 * error. That is the design.
 */
@Repository
public class ListingSearchRepository {

    private final JdbcClient jdbc;

    public ListingSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SearchHit> search(SearchCriteria criteria) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        // Only live listings are ever visible. First condition, and not
        // optional: a filter bug must never expose a draft.
        conditions.add("l.status = 'LIVE'");

        addLocationFilters(criteria, conditions, params);
        addMoneyFilters(criteria, conditions, params);
        addFlatFilters(criteria, conditions, params);
        addKeyset(criteria, conditions, params);

        SortOrder sort = criteria.sort();

        // Fetch one extra row to learn whether another page exists, without a
        // second COUNT query over the whole filtered set.
        params.put("limit", criteria.maxResults() + 1);

        String sql = """
                SELECT l.id, l.area_name, l.nearest_station, l.minutes_to_station,
                       l.monthly_rent_inr, l.deposit_inr, l.maintenance_inr,
                       l.maintenance_included, l.brokerage_inr, l.buyout_inr,
                       l.configuration, l.room_type, l.furnishing,
                       l.available_from, l.published_at,
                       -- The cursor value for this row. Safe to interpolate:
                       -- the column name comes from the SortOrder enum.
                       CAST(l.%s AS text) AS sort_value
                FROM listings l
                WHERE %s
                ORDER BY l.%s %s, l.id %s
                LIMIT :limit
                """.formatted(
                sort.column(),                          // CAST(...) AS sort_value
                String.join("\n  AND ", conditions),    // WHERE
                sort.column(), sort.direction(),        // ORDER BY
                sort.direction());                      // tiebreak direction

        List<SearchHit> rows = jdbc.sql(sql)
                .params(params)
                .query(SearchHit::fromRow)
                .list();

        return rows;
    }

    /** Count for the "{n} flats" header. Separate because most pages do not need it. */
    public long count(SearchCriteria criteria) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        conditions.add("l.status = 'LIVE'");
        addLocationFilters(criteria, conditions, params);
        addMoneyFilters(criteria, conditions, params);
        addFlatFilters(criteria, conditions, params);
        // Deliberately no keyset: a count is of the whole result set, not of
        // what remains after the current page.

        return jdbc.sql("SELECT count(*) FROM listings l WHERE "
                        + String.join("\n  AND ", conditions))
                .params(params)
                .query(Long.class)
                .single();
    }

    // ---- Filter groups ----------------------------------------------------

    private void addLocationFilters(SearchCriteria c, List<String> conditions,
                                    Map<String, Object> params) {
        if (!c.areas().isEmpty()) {
            // = ANY(:areas) rather than IN (...): one bound array parameter
            // instead of a fragment whose length depends on input, so the
            // plan can be cached and there is nothing to concatenate.
            conditions.add("l.area_name = ANY(:areas)");
            params.put("areas", c.areas().toArray(new String[0]));
        }

        if (c.station() != null && !c.station().isBlank()) {
            conditions.add("l.nearest_station = :station");
            params.put("station", c.station());
        }

        if (c.hasLocation()) {
            // ST_DWithin, not ST_Distance(...) < n. Only ST_DWithin can use the
            // GiST index: it tests bounding boxes first and discards most rows
            // before computing any distance. ST_Distance in a WHERE clause
            // forces the distance to be computed for every row in the table.
            conditions.add("""
                    ST_DWithin(l.approx_location,
                               ST_MakePoint(:longitude, :latitude)::geography,
                               :radiusMetres)""");
            params.put("latitude", c.latitude());
            params.put("longitude", c.longitude());
            params.put("radiusMetres", c.radiusMetres().doubleValue());
        }
    }

    private void addMoneyFilters(SearchCriteria c, List<String> conditions,
                                 Map<String, Object> params) {
        if (c.rentMin() != null) {
            conditions.add("l.monthly_rent_inr >= :rentMin");
            params.put("rentMin", c.rentMin());
        }
        if (c.rentMax() != null) {
            conditions.add("l.monthly_rent_inr <= :rentMax");
            params.put("rentMax", c.rentMax());
        }
        if (c.moveInBy() != null) {
            // "I can move in by X" means available on or before X, not exactly X.
            conditions.add("l.available_from <= :moveInBy");
            params.put("moveInBy", Date.valueOf(c.moveInBy()));
        }
    }

    private void addFlatFilters(SearchCriteria c, List<String> conditions,
                                Map<String, Object> params) {
        if (!c.configurations().isEmpty()) {
            conditions.add("l.configuration = ANY(:configurations)");
            params.put("configurations", c.configurations().stream()
                    .map(Configuration::name).toArray(String[]::new));
        }
        if (!c.roomTypes().isEmpty()) {
            conditions.add("l.room_type = ANY(:roomTypes)");
            params.put("roomTypes", c.roomTypes().stream()
                    .map(RoomType::name).toArray(String[]::new));
        }
        if (!c.furnishings().isEmpty()) {
            conditions.add("l.furnishing = ANY(:furnishings)");
            params.put("furnishings", c.furnishings().stream()
                    .map(Furnishing::name).toArray(String[]::new));
        }
        if (c.flatmateGender() != null) {
            // A listing open to ANY matches whatever the searcher asked for;
            // filtering on equality alone would hide most of the inventory.
            conditions.add("(l.flatmate_gender = :flatmateGender OR l.flatmate_gender = 'ANY')");
            params.put("flatmateGender", c.flatmateGender().name());
        }
        if (!c.mustHaveAmenities().isEmpty()) {
            // @> is array containment: "has ALL of these", which is what "must
            // have" means. It is the operator the GIN index on amenities
            // supports; && ("overlaps") would mean "has any of these".
            // Explicit cast: the driver sends String[] as varchar[], and
            // PostgreSQL has no text[] @> varchar[] operator. Scalar filters
            // like = ANY(...) cast implicitly; array-to-array operators do not.
            conditions.add("l.amenities @> CAST(:mustHave AS text[])");
            params.put("mustHave", c.mustHaveAmenities().toArray(new String[0]));
        }
    }

    /**
     * The keyset seek.
     *
     * <p>{@code (sort_column, id) < (value, id)} is PostgreSQL row comparison:
     * it compares left to right, exactly matching the ORDER BY, and the planner
     * turns it into an index seek. The id is included to break ties — without
     * it, two listings published in the same millisecond would make a page
     * boundary ambiguous and one of them would be skipped or repeated.
     */
    private void addKeyset(SearchCriteria c, List<String> conditions,
                           Map<String, Object> params) {
        if (c.cursor() == null) {
            return;
        }
        SortOrder sort = c.sort();
        conditions.add("(l.%s, l.id) %s (CAST(:cursorValue AS %s), CAST(:cursorId AS uuid))"
                .formatted(sort.column(), sort.keysetComparator(), sort.castType()));
        params.put("cursorValue", c.cursor().sortValue());
        params.put("cursorId", c.cursor().id().toString());
    }

    /** Exposed for tests that want to inspect the generated plan. */
    public String explain(SearchCriteria criteria) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        conditions.add("l.status = 'LIVE'");
        addLocationFilters(criteria, conditions, params);
        addMoneyFilters(criteria, conditions, params);
        addFlatFilters(criteria, conditions, params);
        params.put("limit", criteria.maxResults());

        String sql = "EXPLAIN SELECT l.id FROM listings l WHERE %s ORDER BY l.%s %s LIMIT :limit"
                .formatted(String.join(" AND ", conditions),
                        criteria.sort().column(), criteria.sort().direction());

        return String.join("\n", jdbc.sql(sql).params(params).query(String.class).list());
    }
}
