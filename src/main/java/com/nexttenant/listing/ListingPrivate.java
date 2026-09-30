package com.nexttenant.listing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The true location of a listing. <strong>Never rendered on a public page.</strong>
 *
 * <p>Released only when an interest request against this listing reaches
 * {@code CONNECTED}.
 *
 * <p>As with {@code UserPrivate}, there is deliberately no association back to
 * {@link Listing}: the only link is {@link #listingId}. Loading a listing to
 * render a search result therefore cannot pull the exact coordinates along
 * with it.
 *
 * @see Listing
 */
@Entity
@Table(name = "listing_private")
public class ListingPrivate {

    @Id
    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    /**
     * The pin the leaver dragged onto their building.
     *
     * <p>{@code @JdbcTypeCode(GEOGRAPHY)} matters: without it Hibernate assumes
     * a {@code geometry} column, which measures in degrees rather than metres
     * and fails schema validation against our {@code geography(Point, 4326)}.
     *
     * <p>Writing this column fires the database trigger that recomputes
     * {@link Listing#getApproxLocation()}. Nothing in Java performs that
     * calculation, so nothing in Java can skip it.
     */
    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "exact_location")
    private Point exactLocation;

    /**
     * Whether the point above is a real pin or a locality centre.
     *
     * <p>Dropping a pin is no longer compulsory — it was the commonest place
     * people abandoned the wizard, since every other question can be answered
     * from memory and this one asks somebody to find their own building on a
     * map. A recognised area supplies a usable centre instead.
     *
     * <p>Which means {@code exactLocation} is not always exact, and this says
     * so. Without it a centroid would be indistinguishable from a pin, and
     * every later reader would have to assume the worst of every row — or,
     * more likely, would not think to ask at all.
     */
    @Column(name = "location_source", nullable = false)
    @Enumerated(EnumType.STRING)
    private LocationSource locationSource = LocationSource.PIN;

    @Column(name = "full_address", columnDefinition = "text")
    private String fullAddress;

    /**
     * Private by decision, not by oversight. A society name <em>is</em> an
     * address: publishing "Rustomjee Elanza, Malad West" would make the
     * approximate circle decorative, since the name alone locates the building.
     */
    @Column(name = "society_name", length = 120)
    private String societyName;

    @Column(name = "created_at", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected ListingPrivate() {
    }

    public ListingPrivate(UUID listingId) {
        this.listingId = Objects.requireNonNull(listingId, "listingId");
    }

    // ---- Behaviour -------------------------------------------------------

    /**
     * Records where the flat actually is. Persisting this change causes the
     * database to recompute the public approximate point.
     *
     * @param point build it with {@code GeoPoints.of(latitude, longitude)} —
     *              never a raw JTS constructor, whose axis order is reversed
     */
    public void placeAt(Point point) {
        this.exactLocation = point;
        this.locationSource = LocationSource.PIN;
    }

    /**
     * Places the flat at the centre of its locality, because no pin was given.
     *
     * <p>A separate method rather than a flag on { #placeAt}, so that
     * storing something that is not the building is a deliberate call with a
     * name that says what it is. There is no way to write a centroid while
     * believing you wrote a pin.
     */
    public void placeAtAreaCentre(Point centroid) {
        this.exactLocation = centroid;
        this.locationSource = LocationSource.AREA_CENTROID;
    }

    public void setFullAddress(String fullAddress) {
        this.fullAddress = fullAddress;
    }

    public void setSocietyName(String societyName) {
        this.societyName = societyName;
    }

    public boolean hasLocation() {
        return exactLocation != null;
    }

    public LocationSource getLocationSource() {
        return locationSource;
    }

    /** True when the stored point is the building, not the locality. */
    public boolean hasRealPin() {
        return exactLocation != null && locationSource.isExact();
    }

    // ---- Accessors -------------------------------------------------------

    public UUID getListingId() {
        return listingId;
    }

    public Point getExactLocation() {
        return exactLocation;
    }

    public String getFullAddress() {
        return fullAddress;
    }

    public String getSocietyName() {
        return societyName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ListingPrivate other)) {
            return false;
        }
        return Objects.equals(listingId, other.listingId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(listingId);
    }

    @Override
    public String toString() {
        // Prints the id only. A toString() including coordinates or the address
        // would put them into every log line that interpolates this object.
        return "ListingPrivate{listingId=" + listingId + '}';
    }
}
