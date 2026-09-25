package com.househunting.area;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Reads the {@code areas} reference table.
 *
 * <p>JdbcClient rather than JPA: this is read-only reference data, and the
 * autocomplete query uses pg_trgm similarity, which has no JPA expression.
 */
@Repository
public class AreaRepository {

    /**
     * Projects the geography column into plain latitude and longitude, so
     * nothing above this layer has to know about JTS or axis order.
     */
    private static final String SELECT = """
            SELECT id, name, nearest_station,
                   ST_Y(centroid::geometry) AS latitude,
                   ST_X(centroid::geometry) AS longitude,
                   radius_metres
            FROM areas
            """;

    private final JdbcClient jdbc;

    public AreaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Area> findAllActive() {
        return jdbc.sql(SELECT + " WHERE active ORDER BY name")
                .query(Area.class)
                .list();
    }

    public Optional<Area> findByName(String name) {
        return jdbc.sql(SELECT + " WHERE active AND lower(name) = lower(:name)")
                .param("name", name)
                .query(Area.class)
                .optional();
    }

    public long countActive() {
        return jdbc.sql("SELECT count(*) FROM areas WHERE active")
                .query(Long.class)
                .single();
    }

    /**
     * Fuzzy autocomplete over area and station names.
     *
     * <p>{@code %} is pg_trgm's similarity operator: it compares the sets of
     * three-character sequences in two strings, so "andhri" still matches
     * "Andheri" despite a missing letter. A LIKE query could not — 'andhri%'
     * matches nothing, and '%andhri%' matches nothing either, because the
     * strings genuinely differ.
     *
     * <p>A prefix match is kept as a separate, higher-ranked branch: someone
     * typing "ban" expects Bandra first, not whichever name happens to be most
     * trigram-similar.
     */
    public List<Area> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, name, nearest_station,
                               ST_Y(centroid::geometry) AS latitude,
                               ST_X(centroid::geometry) AS longitude,
                               radius_metres
                        FROM areas
                        WHERE active
                          AND (name ILIKE :prefix
                               OR nearest_station ILIKE :prefix
                               OR name % :q
                               OR nearest_station % :q)
                        ORDER BY
                          -- Prefix matches first; similarity decides the rest.
                          (name ILIKE :prefix) DESC,
                          similarity(name, :q) DESC,
                          name
                        LIMIT :limit
                        """)
                .param("prefix", query.trim() + "%")
                .param("q", query.trim())
                .param("limit", limit)
                .query(Area.class)
                .list();
    }
}
