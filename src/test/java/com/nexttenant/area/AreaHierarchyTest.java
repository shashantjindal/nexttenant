package com.nexttenant.area;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Localities, the sublocalities inside them, and the rules that keep the tree
 * exactly two levels deep.
 */
@SpringBootTest
class AreaHierarchyTest {

    @Autowired private AreaRepository areas;
    @Autowired private JdbcClient jdbc;

    // ---- Reading ----------------------------------------------------------

    @Test
    @DisplayName("a sublocality knows the locality it sits in")
    void sublocalityKnowsItsParent() {
        Area diamondGarden = areas.findByName("Diamond Garden").orElseThrow();

        assertThat(diamondGarden.isSublocality()).isTrue();
        assertThat(diamondGarden.parentName()).isEqualTo("Chembur");
        // "Diamond Garden" alone means nothing to somebody who does not
        // already know Chembur — and they are exactly who needs the list.
        assertThat(diamondGarden.subtitle()).isEqualTo("Chembur · Chembur station");
    }

    @Test
    @DisplayName("a sublocality with no centre of its own borrows its parent's")
    void sublocalityInheritsTheCentroid() {
        Area chembur = areas.findByName("Chembur").orElseThrow();
        Area diamondGarden = areas.findByName("Diamond Garden").orElseThrow();

        // We can name a hundred sublocalities; we cannot geocode them, and
        // inventing coordinates would silently return the wrong rooms. So the
        // column is null and resolves to the parent's — a smaller lie, and one
        // that fixing is a pure data task.
        assertThat(diamondGarden.latitude()).isEqualTo(chembur.latitude());
        assertThat(diamondGarden.longitude()).isEqualTo(chembur.longitude());
        assertThat(diamondGarden.radiusMetres()).isEqualTo(chembur.radiusMetres());

        // And never (0, 0), which is what a missing COALESCE would produce —
        // a pin in the Gulf of Guinea.
        assertThat(diamondGarden.latitude()).isNotZero();
    }

    @Test
    @DisplayName("the filter panel gets localities only")
    void topLevelExcludesSublocalities() {
        List<Area> topLevel = areas.findTopLevel();

        assertThat(topLevel).isNotEmpty();
        assertThat(topLevel).noneMatch(Area::isSublocality);
        assertThat(topLevel).extracting(Area::name).contains("Chembur")
                .doesNotContain("Diamond Garden");
    }

    @Test
    @DisplayName("the dropdown groups children under their locality")
    void groupingPutsChildrenUnderParents() {
        AreaRepository.AreaGroup chembur = areas.findGrouped().stream()
                .filter(g -> g.parent().name().equals("Chembur"))
                .findFirst().orElseThrow();

        assertThat(chembur.hasChildren()).isTrue();
        assertThat(chembur.children()).extracting(Area::name).contains("Diamond Garden");
        // Every group's parent is a locality; a group of a group would mean the
        // two-level rule had been broken somewhere.
        assertThat(areas.findGrouped()).allMatch(g -> !g.parent().isSublocality());
    }

    // ---- Expansion --------------------------------------------------------

    @Test
    @DisplayName("asking for a locality means asking for everything inside it")
    void expansionAddsChildren() {
        List<String> expanded = areas.expandWithChildren(List.of("Chembur"));

        // Without this, a search for Chembur would miss a listing that named
        // Diamond Garden — so "the whole locality" would return fewer rooms
        // than one street inside it.
        assertThat(expanded).contains("Chembur", "Diamond Garden", "Chedda Nagar");
    }

    @Test
    @DisplayName("asking for a sublocality stays narrow")
    void expansionDoesNotWidenASublocality() {
        // It has no children of its own — the tree is two levels — so the ask
        // is honoured exactly. Picking Diamond Garden must not quietly return
        // all of Chembur.
        assertThat(areas.expandWithChildren(List.of("Diamond Garden")))
                .containsExactly("Diamond Garden");
    }

    @Test
    @DisplayName("an unknown name passes through rather than erroring")
    void expansionToleratesRubbish() {
        // A shared URL, an old bookmark, a hand-edited query string. It should
        // match nothing, not fail.
        assertThat(areas.expandWithChildren(List.of("Atlantis"))).containsExactly("Atlantis");
        assertThat(areas.expandWithChildren(List.of())).isEmpty();
    }

    @Test
    @DisplayName("expansion is case-insensitive and keeps the caller's order")
    void expansionIsCaseInsensitive() {
        List<String> expanded = areas.expandWithChildren(List.of("CHEMBUR", "Powai"));

        assertThat(expanded).startsWith("CHEMBUR", "Powai");
        assertThat(expanded).contains("Diamond Garden", "Hiranandani Gardens");
        // De-duplicated: nothing should appear twice however the names arrive.
        assertThat(expanded).doesNotHaveDuplicates();
    }

    // ---- Nearby -----------------------------------------------------------

    @Test
    @DisplayName("nearby reaches the next locality over, and not across the city")
    void nearbyReachesNeighbours() {
        List<String> nearby = areas.expandToNearby(List.of("Andheri West"), 3000);

        // Itself first — being near yourself is zero metres, and dropping the
        // original would turn "also show nearby" into "show only nearby".
        assertThat(nearby).contains("Andheri West");
        // Its own sublocalities share its centre, so they are trivially inside
        // any radius.
        assertThat(nearby).contains("Versova", "Lokhandwala");
        // And the localities genuinely beside it.
        assertThat(nearby).contains("Jogeshwari West");

        // Not the far side of Mumbai. This is the assertion that would catch a
        // missing unit conversion — ST_DWithin on a geography takes METRES,
        // and a version of this that passed 3000 degrees would return every
        // area in the table.
        assertThat(nearby).doesNotContain("Colaba", "Thane West", "Panvel");
        assertThat(nearby.size()).isLessThan((int) areas.countActive());
    }

    @Test
    @DisplayName("a wider radius reaches strictly more")
    void nearbyGrowsWithTheRadius() {
        List<String> near = areas.expandToNearby(List.of("Chembur"), 2000);
        List<String> far = areas.expandToNearby(List.of("Chembur"), 8000);

        // Monotonic, which is the property worth pinning: it is what makes the
        // number on the checkbox a dial rather than a magic value.
        assertThat(far).containsAll(near);
        assertThat(far.size()).isGreaterThan(near.size());
    }

    @Test
    @DisplayName("nearby measures from a sublocality's parent centre")
    void nearbyUsesTheInheritedCentre() {
        // Diamond Garden has no centre of its own, so "near Diamond Garden"
        // has to mean "near Chembur". Without the COALESCE the centre would be
        // null, ST_DWithin would return nothing, and the tick would silently
        // narrow the search instead of widening it.
        assertThat(areas.expandToNearby(List.of("Diamond Garden"), 3000))
                .contains("Diamond Garden", "Chembur");
    }

    @Test
    @DisplayName("several areas widen around each of them")
    void nearbyHandlesAMultiSelect() {
        List<String> both = areas.expandToNearby(List.of("Chembur", "Borivali West"), 3000);

        // The reason this is an area-to-area query rather than a radius search
        // over listings: the panel is a multi-select, and a radius takes one
        // point.
        assertThat(both).contains("Chembur", "Borivali West");
        assertThat(both).containsAnyElementsOf(areas.expandToNearby(List.of("Chembur"), 3000));
        assertThat(both).containsAnyElementsOf(areas.expandToNearby(List.of("Borivali West"), 3000));
    }

    @Test
    @DisplayName("an unknown name narrows to nothing rather than widening to everything")
    void nearbyToleratesRubbish() {
        // The failure mode that matters: if a name matches no row, `picked` is
        // empty, the join produces nothing, and an unguarded version would
        // return an empty list — which downstream would read as "no area
        // filter" and show every flat in Mumbai.
        assertThat(areas.expandToNearby(List.of("Atlantis"), 3000)).containsExactly("Atlantis");
        assertThat(areas.expandToNearby(List.of(), 3000)).isEmpty();
    }

    // ---- The two-level rule -----------------------------------------------

    @Test
    @DisplayName("a third level is refused by the database")
    void threeLevelsAreRefused() {
        short chembur = areas.findByName("Chembur").orElseThrow().id();
        short diamondGarden = areas.findByName("Diamond Garden").orElseThrow().id();

        // A self-referencing table with no depth limit is how you get
        // "Chembur > Diamond Garden > Diamond Garden Lane > Building 4" and a
        // dropdown nobody can render. A CHECK cannot see other rows, so this
        // is a trigger.
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO areas (id, name, nearest_station, parent_id)
                        VALUES (9001, 'Some Lane', 'Chembur', ?)
                        """)
                .param(diamondGarden)
                .update())
                .hasMessageContaining("three levels");

        // The same insert one level up is fine, which is what proves the
        // trigger is rejecting depth rather than rejecting everything.
        jdbc.sql("""
                        INSERT INTO areas (id, name, nearest_station, parent_id)
                        VALUES (9002, 'A Test Sublocality', 'Chembur', ?)
                        """)
                .param(chembur)
                .update();
        jdbc.sql("DELETE FROM areas WHERE id = 9002").update();
    }

    @Test
    @DisplayName("an area cannot be its own parent")
    void selfParentingIsRefused() {
        short chembur = areas.findByName("Chembur").orElseThrow().id();

        assertThatThrownBy(() -> jdbc.sql("UPDATE areas SET parent_id = ? WHERE id = ?")
                .param(chembur).param(chembur).update())
                .hasMessageContaining("its own parent");
    }

    @Test
    @DisplayName("a locality must have a centre; a sublocality need not")
    void topLevelRequiresACentroid() {
        // Nothing sits above a locality to fall back to, so a null centre there
        // would make every radius search from it return nothing at all — and
        // return it silently, which is the worst kind of empty result.
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO areas (id, name, nearest_station) VALUES
                        (9003, 'A Rootless Place', 'Nowhere')
                        """)
                .update())
                .hasMessageContaining("areas_top_level_needs_centroid_ck");
    }
}
