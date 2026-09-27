package com.nexttenant.listing;

import com.nexttenant.geo.GeoPoints;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trips listings against real PostgreSQL + PostGIS, and verifies that the
 * privacy mechanism holds through the Java layer.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ListingPersistenceTest {

    /** Bandra West, Mumbai. */
    private static final double LAT = 19.0596;
    private static final double LNG = 72.8295;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private ListingPrivateRepository listingPrivateRepository;

    /** Listings need an owner; this creates one directly to avoid a cross-package dependency. */
    private UUID insertLeaver() {
        UUID id = UUID.randomUUID();
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO users (id, first_name) VALUES (:id, 'Priya')")
                .setParameter("id", id)
                .executeUpdate();
        return id;
    }

    @Test
    @DisplayName("a draft listing round-trips, including enums and text arrays")
    void listingRoundTrips() {
        Listing listing = new Listing(insertLeaver());
        listing.setLocationDetails("Bandra West", "Bandra", (short) 8, (short) 7, true);
        listing.setFlatDetails(Configuration.BHK_2, RoomType.PRIVATE_ROOM,
                HandoverType.ROOM_WITH_FURNITURE, (short) 2, Furnishing.SEMI_FURNISHED);
        listing.setAmenities(List.of("LIFT", "PARKING", "WIFI"));
        listing.setMoney(32000, 64000, 2500, false, 0, 15000);
        listing.setAvailableFrom(LocalDate.of(2026, 11, 1));

        listingRepository.save(listing);
        entityManager.flush();
        entityManager.clear();

        Listing loaded = listingRepository.findById(listing.getId()).orElseThrow();

        assertThat(loaded.getAreaName()).isEqualTo("Bandra West");
        assertThat(loaded.getConfiguration()).isEqualTo(Configuration.BHK_2);
        assertThat(loaded.getRoomType()).isEqualTo(RoomType.PRIVATE_ROOM);
        assertThat(loaded.getFurnishing()).isEqualTo(Furnishing.SEMI_FURNISHED);
        assertThat(loaded.getAmenities()).containsExactly("LIFT", "PARKING", "WIFI");
        assertThat(loaded.getStatus()).isEqualTo(ListingStatus.DRAFT);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("enum labels come from the enum, not from templates")
    void enumsCarryDisplayLabels() {
        assertThat(Configuration.BHK_2.getLabel()).isEqualTo("2 BHK");
        assertThat(Configuration.RK.getLabel()).isEqualTo("1 RK");
        assertThat(RoomType.PRIVATE_ROOM.getLabel()).isEqualTo("Private room");
        assertThat(FlatmateGender.ANY.getLabel()).isEqualTo("Anyone");
    }

    @Test
    @DisplayName("saving the private location makes the database populate the public approximate point")
    void triggerPopulatesApproximateLocation() {
        Listing listing = listingRepository.save(new Listing(insertLeaver()));
        entityManager.flush();

        // Before any private row exists there is no published point.
        assertThat(listing.getApproxLocation()).isNull();

        ListingPrivate priv = new ListingPrivate(listing.getId());
        priv.placeAt(GeoPoints.of(LAT, LNG));
        priv.setFullAddress("Flat 702, Sunbeam Apartments, 14th Road, Bandra West");
        priv.setSocietyName("Sunbeam Apartments");
        listingPrivateRepository.save(priv);
        entityManager.flush();
        entityManager.clear();

        Listing reloaded = listingRepository.findById(listing.getId()).orElseThrow();
        Point published = reloaded.getApproxLocation();

        assertThat(published).isNotNull();

        // It is NOT the real point, and it is in the right neighbourhood.
        assertThat(GeoPoints.latitudeOf(published)).isNotEqualTo(LAT);
        assertThat(GeoPoints.isWithinMumbai(published)).isTrue();
    }

    @Test
    @DisplayName("the published point is a stable 120-300m from the truth")
    void approximateLocationIsOffsetWithinBounds() {
        Listing listing = listingRepository.save(new Listing(insertLeaver()));
        ListingPrivate priv = new ListingPrivate(listing.getId());
        priv.placeAt(GeoPoints.of(LAT, LNG));
        listingPrivateRepository.save(priv);
        entityManager.flush();
        entityManager.clear();

        Double metres = (Double) entityManager.getEntityManager().createNativeQuery("""
                        SELECT ST_Distance(p.exact_location, l.approx_location)
                        FROM listings l JOIN listing_private p ON p.listing_id = l.id
                        WHERE l.id = :id
                        """)
                .setParameter("id", listing.getId())
                .getSingleResult();

        assertThat(metres).isBetween(120.0, 300.0);
    }

    @Test
    @DisplayName("Java cannot write approx_location even by reflection, because the column is not insertable")
    void approximateLocationIsReadOnlyFromJava() throws Exception {
        Listing listing = new Listing(insertLeaver());

        // There is deliberately no setter, so this bypasses the public API
        // entirely to prove the guarantee is structural rather than a matter of
        // API discipline. If insertable=false were missing, this exact point
        // would land in a public column.
        Point realLocation = GeoPoints.of(LAT, LNG);
        Field field = Listing.class.getDeclaredField("approxLocation");
        field.setAccessible(true);
        field.set(listing, realLocation);

        listingRepository.save(listing);
        entityManager.flush();
        entityManager.clear();

        Listing loaded = listingRepository.findById(listing.getId()).orElseThrow();

        // Hibernate omitted the column from the INSERT, so the database still
        // holds NULL: the smuggled value never reached it.
        assertThat(loaded.getApproxLocation()).isNull();
    }

    @Test
    @DisplayName("move-in total is computed from its parts rather than stored")
    void movesInCostIsDerived() {
        Listing listing = new Listing(UUID.randomUUID());

        listing.setMoney(32000, 64000, 2500, false, 0, 15000);
        // rent + deposit + maintenance + brokerage + buyout
        assertThat(listing.totalMoveInCostInr()).isEqualTo(32000 + 64000 + 2500 + 0 + 15000);

        // Maintenance already inside the rent must not be added twice.
        listing.setMoney(32000, 64000, 2500, true, 0, 0);
        assertThat(listing.totalMoveInCostInr()).isEqualTo(32000 + 64000);

        assertThat(listing.isZeroBrokerage()).isTrue();

        // A draft with no rent has no total, rather than a misleading zero.
        Listing draft = new Listing(UUID.randomUUID());
        assertThat(draft.totalMoveInCostInr()).isNull();
    }

    @Test
    @DisplayName("the PostGIS radius query finds live listings near a point")
    void radiusSearchFindsNearbyListings() {
        Listing listing = new Listing(insertLeaver());
        listing.setLocationDetails("Bandra West", "Bandra", (short) 8, (short) 7, true);
        listing.setFlatDetails(Configuration.BHK_2, RoomType.PRIVATE_ROOM,
                HandoverType.ROOM_ONLY, (short) 2, Furnishing.SEMI_FURNISHED);
        listing.setMoney(32000, 64000, null, false, 0, 0);
        listing.setAvailableFrom(LocalDate.of(2026, 11, 1));
        listingRepository.save(listing);
        entityManager.flush();

        ListingPrivate priv = new ListingPrivate(listing.getId());
        priv.placeAt(GeoPoints.of(LAT, LNG));
        listingPrivateRepository.save(priv);
        entityManager.flush();

        // Publishing requires approx_location, which the trigger has now set.
        listing.publish(Instant.now(), 14);
        listingRepository.save(listing);
        entityManager.flush();
        entityManager.clear();

        // Bandra station is roughly 1.3km away, so a 3km radius should match
        // and a 100m radius should not.
        assertThat(listingRepository.findLiveNear(19.0544, 72.8404, 3000))
                .extracting(Listing::getId)
                .contains(listing.getId());

        assertThat(listingRepository.findLiveNear(19.0544, 72.8404, 100))
                .extracting(Listing::getId)
                .doesNotContain(listing.getId());
    }

    @Test
    @DisplayName("lifecycle transitions behave as the dashboard expects")
    void lifecycleTransitions() {
        Listing listing = new Listing(UUID.randomUUID());
        Instant now = Instant.now();

        assertThat(listing.getStatus()).isEqualTo(ListingStatus.DRAFT);
        assertThat(listing.isPubliclyVisible()).isFalse();

        listing.publish(now, 14);
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.LIVE);
        assertThat(listing.isPubliclyVisible()).isTrue();
        assertThat(listing.getExpiresAt()).isEqualTo(now.plusSeconds(14 * 86400L));

        // "Extend 14 days" adds to the existing expiry, not to today, so
        // extending early does not shorten the listing.
        listing.extend(now, 14);
        assertThat(listing.getExpiresAt()).isEqualTo(now.plusSeconds(28 * 86400L));

        listing.pause();
        assertThat(listing.isPubliclyVisible()).isFalse();
        assertThat(listing.getStatus().isResumable()).isTrue();

        listing.markRented(now);
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.RENTED);
        assertThat(listing.getRentedAt()).isEqualTo(now);
    }
}
