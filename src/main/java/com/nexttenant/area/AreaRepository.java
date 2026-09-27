package com.nexttenant.area;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     *
     * <p>The LEFT JOIN and the COALESCEs are the sublocality rule made
     * executable: a child with no centre of its own borrows its parent's. That
     * resolution happens HERE, once, rather than in each caller — a caller that
     * forgot would read null coordinates and drop a pin in the Gulf of Guinea,
     * which is where (0, 0) is.
     */
    private static final String SELECT = """
            SELECT a.id,
                   a.name,
                   a.nearest_station,
                   ST_Y(COALESCE(a.centroid, p.centroid)::geometry)  AS latitude,
                   ST_X(COALESCE(a.centroid, p.centroid)::geometry)  AS longitude,
                   COALESCE(a.radius_metres, p.radius_metres)        AS radius_metres,
                   a.parent_id,
                   p.name                                            AS parent_name
            FROM areas a
            LEFT JOIN areas p ON p.id = a.parent_id
            """;

    private final JdbcClient jdbc;

    public AreaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every active area, localities and sublocalities alike. */
    public List<Area> findAllActive() {
        return jdbc.sql(SELECT + " WHERE a.active ORDER BY a.name")
                .query(Area.class)
                .list();
    }

    /**
     * Localities only — no sublocalities.
     *
     * <p>Used by the search filter panel. Listing all 200-odd rows as
     * checkboxes would make the panel unusable, and it would also be
     * redundant: ticking "Chembur" already includes everything inside it (see
     * {@link #expandWithChildren}). Sublocalities are still reachable there,
     * through the autocomplete, which searches everything.
     */
    public List<Area> findTopLevel() {
        return jdbc.sql(SELECT + " WHERE a.active AND a.parent_id IS NULL ORDER BY a.name")
                .query(Area.class)
                .list();
    }

    /**
     * Everything, arranged for a grouped dropdown.
     *
     * <p>Grouping in Java rather than relying on the template to notice when
     * the parent changes: an {@code <optgroup>} has to wrap its options, and a
     * template that opens and closes tags based on a comparison with the
     * previous row is the kind of thing that produces invalid HTML the day
     * somebody adds a sort.
     */
    public List<AreaGroup> findGrouped() {
        List<Area> all = findAllActive();

        Map<Short, List<Area>> childrenByParent = new LinkedHashMap<>();
        List<Area> parents = new ArrayList<>();

        for (Area area : all) {
            if (area.isSublocality()) {
                childrenByParent.computeIfAbsent(area.parentId(), k -> new ArrayList<>()).add(area);
            } else {
                parents.add(area);
            }
        }

        return parents.stream()
                .map(parent -> new AreaGroup(parent,
                        childrenByParent.getOrDefault(parent.id(), List.of())))
                .toList();
    }

    /**
     * A locality and the sublocalities inside it.
     *
     * @param children empty for a locality nobody has broken down yet, which is
     *                 most of them — the sublocality list in V13 is a starter
     *                 set, not a complete one
     */
    public record AreaGroup(Area parent, List<Area> children) {
        public boolean hasChildren() {
            return !children.isEmpty();
        }
    }

    public Optional<Area> findByName(String name) {
        return jdbc.sql(SELECT + " WHERE a.active AND lower(a.name) = lower(:name)")
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
     * Adds every sublocality of every named locality.
     *
     * <p>Search matches {@code listings.area_name} against a list of names. A
     * listing in Diamond Garden stores "Diamond Garden", so a search for
     * "Chembur" would not match it — asking for the whole locality would return
     * fewer results than asking for one street inside it, which is nonsense the
     * moment anybody notices it.
     *
     * <p>Expanding here rather than in SQL keeps the search query unchanged: it
     * still filters on one array of names, it is just a longer array. A join to
     * `areas` inside the search would put reference data in the hot path of
     * every query for the sake of a lookup that changes once a year.
     *
     * <p>Names not in the table at all — an old bookmark, a hand-edited URL —
     * pass through untouched, and simply match nothing.
     */
    public List<String> expandWithChildren(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }

        List<String> children = jdbc.sql("""
                        SELECT c.name
                        FROM areas c
                        JOIN areas p ON p.id = c.parent_id
                        WHERE c.active AND lower(p.name) = ANY(:names)
                        """)
                .param("names", names.stream().map(String::toLowerCase).toArray(String[]::new))
                .query(String.class)
                .list();

        if (children.isEmpty()) {
            return names;
        }

        // LinkedHashSet: de-duplicated, but the caller's order preserved, so a
        // search URL's meaning does not depend on hash iteration order.
        var combined = new java.util.LinkedHashSet<>(names);
        combined.addAll(children);
        return List.copyOf(combined);
    }

    /**
     * Adds every area whose centre is within {@code metres} of a named one.
     *
     * <p><b>What this measures, and what it does not.</b> It compares area
     * CENTRE to area CENTRE. "Andheri West is within 3km of Jogeshwari West"
     * means their centres are — not that any particular flat is. A large
     * locality whose centre sits 3.5km away is excluded even where its near
     * edge touches yours, and a small one 2.9km away is included whole.
     *
     * <p>The alternative would be a true radius search over listing locations,
     * which is more accurate and already exists in this codebase — it is what
     * happens when somebody picks an area from the autocomplete instead of
     * ticking checkboxes. It is not used here because it takes ONE point, and
     * this panel lets you tick five areas at once. Answering "near any of
     * these five" as a spatial query means five queries or a union; answering
     * it as "which areas are near any of these" is one query over ~200 rows of
     * reference data.
     *
     * <p>So the trade is deliberate: a coarser notion of nearby, in exchange
     * for one that composes with a multi-select. The label on the checkbox
     * says "nearby areas" rather than "within 3km of your flat" for exactly
     * this reason.
     *
     * <p>The COALESCE mirrors {@link #SELECT}: a sublocality with no centre of
     * its own is measured from its parent's.
     */
    public List<String> expandToNearby(List<String> names, int metres) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }

        List<String> nearby = jdbc.sql("""
                        WITH resolved AS (
                            SELECT a.name,
                                   COALESCE(a.centroid, p.centroid) AS centre
                            FROM areas a
                            LEFT JOIN areas p ON p.id = a.parent_id
                            WHERE a.active
                        ),
                        picked AS (
                            SELECT centre FROM resolved WHERE lower(name) = ANY(:names)
                        )
                        SELECT DISTINCT r.name
                        FROM resolved r
                        JOIN picked pk ON ST_DWithin(r.centre, pk.centre, :metres)
                        """)
                .param("names", names.stream().map(String::toLowerCase).toArray(String[]::new))
                .param("metres", metres)
                .query(String.class)
                .list();

        if (nearby.isEmpty()) {
            // No match in the table at all — an old bookmark, a hand-edited
            // URL. Hand the names back unchanged so the search narrows to
            // nothing rather than silently widening to everything.
            return names;
        }

        // The caller's own areas first, then the neighbours. Order is not
        // functionally important — it becomes one array in an = ANY — but a
        // stable order keeps a logged query readable.
        var combined = new java.util.LinkedHashSet<>(names);
        combined.addAll(nearby);
        return List.copyOf(combined);
    }

    /**
     * Fuzzy autocomplete over area, sublocality and station names.
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
     *
     * <p>Sublocalities rank BELOW their parents at equal similarity. Somebody
     * typing "chem" almost always wants Chembur itself; the thirteen places
     * inside it are there for the person who knows exactly where they live.
     */
    public List<Area> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return jdbc.sql(SELECT + """
                        WHERE a.active
                          AND (a.name ILIKE :prefix
                               OR a.nearest_station ILIKE :prefix
                               OR a.name % :q
                               OR a.nearest_station % :q)
                        ORDER BY
                          (a.name ILIKE :prefix) DESC,
                          (a.parent_id IS NULL) DESC,
                          similarity(a.name, :q) DESC,
                          a.name
                        LIMIT :limit
                        """)
                .param("prefix", query.trim() + "%")
                .param("q", query.trim())
                .param("limit", limit)
                .query(Area.class)
                .list();
    }
}
