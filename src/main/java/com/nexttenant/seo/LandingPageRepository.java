package com.nexttenant.seo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The lookup table behind every clean URL.
 *
 * <p>A slug resolves here or it does not resolve at all. That is the whole
 * defence against an infinite URL space: {@code /flats-in-anything} is a 404
 * unless {@code anything} is a row in {@code areas}, so a crawler cannot
 * generate pages by inventing path segments.
 */
@Repository
public class LandingPageRepository {

    private static final String SELECT_AREA = """
            SELECT a.id,
                   a.name,
                   a.slug,
                   a.nearest_station,
                   a.station_slug,
                   p.name AS parent_name,
                   a.intro,
                   a.meta_description
              FROM areas a
              LEFT JOIN areas p ON p.id = a.parent_id
            """;

    private final JdbcClient jdbc;

    public LandingPageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AreaLanding> findAreaBySlug(String slug) {
        return jdbc.sql(SELECT_AREA + " WHERE a.active AND a.slug = :slug")
                .param("slug", slug)
                .query(AreaLanding.class)
                .optional();
    }

    /**
     * The areas a station serves.
     *
     * <p>Several rows, which is the point of a station page: "near Andheri
     * station" is the question somebody asks before they have decided between
     * Andheri East and Andheri West, and it is a broader page than either.
     *
     * <p>Localities only. Including sublocalities would list Four Bungalows and
     * Lokhandwala alongside Andheri West, which already contains them — the
     * same rooms counted twice in a list meant to help somebody choose.
     */
    public List<AreaLanding> findAreasByStationSlug(String stationSlug) {
        return jdbc.sql(SELECT_AREA + """
                         WHERE a.active
                           AND a.parent_id IS NULL
                           AND a.station_slug = :slug
                         ORDER BY a.name
                        """)
                .param("slug", stationSlug)
                .query(AreaLanding.class)
                .list();
    }

    /**
     * Area slugs that have at least one live listing, for the sitemap.
     *
     * <p>The filter is the difference between a sitemap and a list of
     * everything. Submitting two hundred area pages when eleven have any rooms
     * on them is submitting a hundred and eighty-nine thin pages for indexing,
     * and the pages do not become good by being in a sitemap — they become a
     * reason to trust the rest of it less.
     *
     * <p>The join is on {@code area_name} because that is how a listing records
     * where it is; see the note in V13 about names staying globally unique.
     */
    @Transactional(readOnly = true)
    public List<SlugWithChange> areaSlugsWithListings() {
        return jdbc.sql("""
                        SELECT a.slug, max(l.updated_at) AS last_changed
                          FROM areas a
                          JOIN listings l ON lower(l.area_name) = lower(a.name)
                         WHERE a.active
                           AND l.status = 'LIVE'
                         GROUP BY a.slug
                         ORDER BY a.slug
                        """)
                .query(SlugWithChange.class)
                .list();
    }

    /** Station slugs with at least one live listing in any area they serve. */
    @Transactional(readOnly = true)
    public List<SlugWithChange> stationSlugsWithListings() {
        return jdbc.sql("""
                        SELECT a.station_slug AS slug, max(l.updated_at) AS last_changed
                          FROM areas a
                          JOIN listings l ON lower(l.area_name) = lower(a.name)
                         WHERE a.active
                           AND a.parent_id IS NULL
                           AND l.status = 'LIVE'
                         GROUP BY a.station_slug
                         ORDER BY a.station_slug
                        """)
                .query(SlugWithChange.class)
                .list();
    }

    /**
     * A slug and when its content last moved.
     *
     * @param lastChanged the newest listing change in that area, which is what
     *                    {@code <lastmod>} should reflect: the page is a list
     *                    of listings, so it changed when they did
     */
    public record SlugWithChange(String slug, Instant lastChanged) {
    }

    /** Areas an editor can write about: the localities, with or without prose. */
    @Transactional(readOnly = true)
    public List<AreaLanding> allLocalities() {
        return jdbc.sql(SELECT_AREA + " WHERE a.active AND a.parent_id IS NULL ORDER BY a.name")
                .query(AreaLanding.class)
                .list();
    }

    /**
     * Saves the prose for one area.
     *
     * <p>Blank is stored as NULL rather than as an empty string, so that
     * "nobody has written this yet" is one state rather than two. The check
     * constraint on the column would reject the empty string anyway; this makes
     * clearing an intro work rather than fail.
     */
    @Transactional
    public void saveIntro(short areaId, String intro, String metaDescription) {
        jdbc.sql("""
                        UPDATE areas
                           SET intro = :intro,
                               meta_description = :meta,
                               intro_updated_at = now()
                         WHERE id = :id
                        """)
                .param("id", areaId)
                .param("intro", blankToNull(intro))
                .param("meta", blankToNull(metaDescription))
                .update();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
