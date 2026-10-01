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
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The PUBLIC face of a listing. Everything here may be rendered to a stranger.
 *
 * <p>Exact coordinates, the full address and the society name live in
 * {@link ListingPrivate}, with no association between the two. Rendering a
 * search result therefore cannot load the real location.
 *
 * @see ListingPrivate
 */
@Entity
@Table(name = "listings")
public class Listing {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /**
     * Plain id rather than {@code @ManyToOne User}.
     *
     * <p>Not a privacy matter here — the public profile is safe to show — but a
     * lazy association would fail when touched during template rendering, since
     * {@code open-in-view} is off, and an eager one would join on every query
     * whether or not the name was needed. Loading the leaver explicitly keeps
     * the number of queries visible at the call site.
     */
    @Column(name = "leaver_user_id", nullable = false, updatable = false)
    private UUID leaverUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ListingStatus status = ListingStatus.DRAFT;

    /** Where "Save and exit" resumes in the six-step wizard. */
    @Column(name = "wizard_step", nullable = false)
    private short wizardStep = 1;

    // ---- Location, approximate only --------------------------------------

    @Column(name = "area_name", length = 80)
    private String areaName;

    @Column(name = "nearest_station", length = 80)
    private String nearestStation;

    @Column(name = "minutes_to_station")
    private Short minutesToStation;

    /**
     * The published circle centre — a deterministic 120-300m offset from the
     * true point.
     *
     * <p><strong>Read-only by construction.</strong> {@code insertable = false,
     * updatable = false} means Hibernate omits this column from every INSERT
     * and UPDATE it generates, so no Java code can write it. The database
     * trigger on {@link ListingPrivate} is the sole author. There is therefore
     * no bug, refactor or well-meaning setter that could place the real
     * coordinates in a public column.
     */
    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "approx_location", insertable = false, updatable = false)
    private Point approxLocation;

    @Column(name = "floor")
    private Short floor;

    @Column(name = "has_lift")
    private Boolean hasLift;

    // ---- The flat ---------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "configuration", length = 20)
    private Configuration configuration;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 20)
    private RoomType roomType;

    @Enumerated(EnumType.STRING)
    @Column(name = "handover_type", length = 30)
    private HandoverType handoverType;

    @Column(name = "existing_flatmates")
    private Short existingFlatmates;

    @Enumerated(EnumType.STRING)
    @Column(name = "furnishing", length = 20)
    private Furnishing furnishing;

    /**
     * Gated society or standalone building.
     *
     * <p>Asked because people ask: it decides whether a watchman logs visitors,
     * what hours guests may come, and how the building treats tenants.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "flat_type", length = 20)
    private FlatType flatType;

    /**
     * The leaver's own words.
     *
     * <p>Every other field on a listing is a dropdown, and no dropdown says
     * "the flatmates are easy going and we cook together on Sundays" — which
     * is the part that makes somebody choose this room over an identical one
     * at the same price.
     */
    @Column(name = "description", columnDefinition = "text")
    private String description;

    /**
     * Maps to {@code TEXT[]}. Kept as free strings for now: the exact
     * vocabulary comes from the wizard's checkbox list, and inventing it here
     * would mean guessing at the product. Held privately and exposed as an
     * immutable list so callers cannot mutate the entity's internals.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "amenities", nullable = false)
    private String[] amenities = new String[0];

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "perks", nullable = false)
    private String[] perks = new String[0];

    // ---- Money, whole rupees ---------------------------------------------
    // Integer (boxed) rather than int: a draft legitimately has no rent yet,
    // and 0 would be indistinguishable from "free".

    @Column(name = "monthly_rent_inr")
    private Integer monthlyRentInr;

    @Column(name = "deposit_inr")
    private Integer depositInr;

    @Column(name = "maintenance_inr")
    private Integer maintenanceInr;

    @Column(name = "maintenance_included", nullable = false)
    private boolean maintenanceIncluded = false;

    @Column(name = "brokerage_inr", nullable = false)
    private int brokerageInr = 0;

    @Column(name = "buyout_inr", nullable = false)
    private int buyoutInr = 0;

    // ---- Handover terms ---------------------------------------------------

    @Column(name = "owner_informed")
    private Boolean ownerInformed;

    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_paid_to", length = 20)
    private DepositPaidTo depositPaidTo;

    @Column(name = "registered_agreement")
    private Boolean registeredAgreement;

    @Enumerated(EnumType.STRING)
    @Column(name = "agreement_handling", length = 30)
    private AgreementHandling agreementHandling;

    @Column(name = "notice_period_days")
    private Short noticePeriodDays;

    @Column(name = "bachelors_allowed")
    private Boolean bachelorsAllowed;

    // ---- Flatmates and house rules ---------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "flatmate_gender", length = 20)
    private FlatmateGender flatmateGender;

    @Column(name = "flatmate_professions", length = 160)
    private String flatmateProfessions;

    @Column(name = "non_veg_allowed")
    private Boolean nonVegAllowed;

    @Column(name = "pets_allowed")
    private Boolean petsAllowed;

    @Column(name = "smoking_allowed")
    private Boolean smokingAllowed;

    @Column(name = "guests_allowed")
    private Boolean guestsAllowed;

    @Column(name = "house_rules_note", columnDefinition = "text")
    private String houseRulesNote;

    // ---- Lifecycle --------------------------------------------------------

    @Column(name = "available_from")
    private LocalDate availableFrom;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "rented_at")
    private Instant rentedAt;

    @Column(name = "taken_down_at")
    private Instant takenDownAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected Listing() {
    }

    /** Starts a new draft owned by the given user. */
    public Listing(UUID leaverUserId) {
        this.leaverUserId = Objects.requireNonNull(leaverUserId, "leaverUserId");
    }

    // ---- Lifecycle behaviour ---------------------------------------------

    /**
     * Publishes the listing for the given number of days.
     *
     * <p>The database also enforces completeness: listings_live_complete_ck
     * rejects a LIVE row missing rent, deposit, area, availability or the
     * approximate point. That constraint is the guarantee; this method is the
     * ordinary path to satisfying it.
     */
    public void publish(Instant now, int liveDays) {
        this.status = ListingStatus.LIVE;
        this.publishedAt = now;
        this.expiresAt = now.plusSeconds(liveDays * 86400L);
        this.takenDownAt = null;
    }

    /** "Extend 14 days" on the dashboard. */
    public void extend(Instant now, int days) {
        Instant from = (expiresAt != null && expiresAt.isAfter(now)) ? expiresAt : now;
        this.expiresAt = from.plusSeconds(days * 86400L);
        if (status == ListingStatus.EXPIRED) {
            this.status = ListingStatus.LIVE;
        }
    }

    public void pause() {
        this.status = ListingStatus.PAUSED;
    }

    public void resume() {
        this.status = ListingStatus.LIVE;
    }

    /** "Mark as rented" — the outcome the product exists to produce. */
    public void markRented(Instant now) {
        this.status = ListingStatus.RENTED;
        this.rentedAt = now;
    }

    /** "Take it down" — withdrawn by the owner, or removed by moderation. */
    public void takeDown(Instant now) {
        this.status = ListingStatus.TAKEN_DOWN;
        this.takenDownAt = now;
    }

    public void expire() {
        this.status = ListingStatus.EXPIRED;
    }

    public void advanceWizardTo(int step) {
        if (step < 1 || step > 6) {
            throw new IllegalArgumentException("wizard step must be 1-6, got " + step);
        }
        this.wizardStep = (short) step;
    }

    // ---- Derived values ---------------------------------------------------

    /**
     * What a taker pays on day one — the figure the design puts on every card.
     *
     * <p>Computed rather than stored: a stored total would silently disagree
     * with its parts the moment any of them changed.
     */
    public Integer totalMoveInCostInr() {
        if (monthlyRentInr == null || depositInr == null) {
            return null;
        }
        int total = monthlyRentInr + depositInr + brokerageInr + buyoutInr;
        if (maintenanceInr != null && !maintenanceIncluded) {
            total += maintenanceInr;
        }
        return total;
    }

    /** Drives the "Zero brokerage" badge. */
    public boolean isZeroBrokerage() {
        return brokerageInr == 0;
    }

    public boolean isPubliclyVisible() {
        return status.isPublic();
    }

    public boolean isOwnedBy(UUID userId) {
        return leaverUserId.equals(userId);
    }

    /**
     * Where this listing lives on the site.
     *
     * <p>Here as well as on the DTOs so that code holding the entity — the
     * wizard finishing a publish, for one — can redirect to the canonical
     * address directly instead of to the bare id and letting the controller
     * bounce it. Both forms work; only one avoids a wasted round trip on the
     * proudest moment of the flow.
     */
    public String path() {
        return ListingUrls.path(
                id,
                areaName,
                configuration == null ? null : configuration.getLabel(),
                roomType == null ? null : roomType.getLabel());
    }

    // ---- Accessors --------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getLeaverUserId() {
        return leaverUserId;
    }

    public ListingStatus getStatus() {
        return status;
    }

    public short getWizardStep() {
        return wizardStep;
    }

    public String getAreaName() {
        return areaName;
    }

    public String getNearestStation() {
        return nearestStation;
    }

    public Short getMinutesToStation() {
        return minutesToStation;
    }

    /** Read-only: maintained by the database trigger. There is no setter. */
    public Point getApproxLocation() {
        return approxLocation;
    }

    public Short getFloor() {
        return floor;
    }

    public Boolean getHasLift() {
        return hasLift;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public HandoverType getHandoverType() {
        return handoverType;
    }

    public Short getExistingFlatmates() {
        return existingFlatmates;
    }

    public Furnishing getFurnishing() {
        return furnishing;
    }

    public FlatType getFlatType() {
        return flatType;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Everything the first screen of the wizard asks for.
     *
     * <p>One method rather than eight setters because these are answered
     * together and saved together: a listing with a configuration but no room
     * type is not a state any screen can produce, and offering the setters
     * separately would invite one.
     */
    public void setBasics(String areaName, Configuration configuration, RoomType roomType,
                          Furnishing furnishing, FlatType flatType,
                          FlatmateGender flatmateGender, LocalDate availableFrom,
                          Short existingFlatmates) {
        this.areaName = areaName;
        this.configuration = configuration;
        this.roomType = roomType;
        this.furnishing = furnishing;
        this.flatType = flatType;
        this.flatmateGender = flatmateGender;
        this.availableFrom = availableFrom;
        this.existingFlatmates = existingFlatmates;
    }

    /**
     * The nearest station, derived rather than asked for.
     *
     * <p>The wizard used to make people type this. Every area already knows
     * its own station, so the question was asking for something the system
     * had — and it is the single most useful fact about a Mumbai address, so
     * dropping the question had to not mean dropping the answer.
     */
    public void setStation(String nearestStation) {
        this.nearestStation = nearestStation;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /** Immutable view: callers cannot reach in and mutate the entity's array. */
    public List<String> getAmenities() {
        return List.of(amenities);
    }

    public List<String> getPerks() {
        return List.of(perks);
    }

    public Integer getMonthlyRentInr() {
        return monthlyRentInr;
    }

    public Integer getDepositInr() {
        return depositInr;
    }

    public Integer getMaintenanceInr() {
        return maintenanceInr;
    }

    public boolean isMaintenanceIncluded() {
        return maintenanceIncluded;
    }

    public int getBrokerageInr() {
        return brokerageInr;
    }

    public int getBuyoutInr() {
        return buyoutInr;
    }

    public Boolean getOwnerInformed() {
        return ownerInformed;
    }

    public DepositPaidTo getDepositPaidTo() {
        return depositPaidTo;
    }

    public Boolean getRegisteredAgreement() {
        return registeredAgreement;
    }

    public AgreementHandling getAgreementHandling() {
        return agreementHandling;
    }

    public Short getNoticePeriodDays() {
        return noticePeriodDays;
    }

    public Boolean getBachelorsAllowed() {
        return bachelorsAllowed;
    }

    public FlatmateGender getFlatmateGender() {
        return flatmateGender;
    }

    public String getFlatmateProfessions() {
        return flatmateProfessions;
    }

    public Boolean getNonVegAllowed() {
        return nonVegAllowed;
    }

    public Boolean getPetsAllowed() {
        return petsAllowed;
    }

    public Boolean getSmokingAllowed() {
        return smokingAllowed;
    }

    public Boolean getGuestsAllowed() {
        return guestsAllowed;
    }

    public String getHouseRulesNote() {
        return houseRulesNote;
    }

    public LocalDate getAvailableFrom() {
        return availableFrom;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRentedAt() {
        return rentedAt;
    }

    public Instant getTakenDownAt() {
        return takenDownAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // ---- Wizard mutators --------------------------------------------------
    // Grouped by wizard step rather than exposed one field at a time, so a
    // caller sets a coherent slice of the listing in a single call.

    public void setLocationDetails(String areaName, String nearestStation,
                                   Short minutesToStation, Short floor, Boolean hasLift) {
        this.areaName = areaName;
        this.nearestStation = nearestStation;
        this.minutesToStation = minutesToStation;
        this.floor = floor;
        this.hasLift = hasLift;
    }

    public void setFlatDetails(Configuration configuration, RoomType roomType,
                               HandoverType handoverType, Short existingFlatmates,
                               Furnishing furnishing) {
        this.configuration = configuration;
        this.roomType = roomType;
        this.handoverType = handoverType;
        this.existingFlatmates = existingFlatmates;
        this.furnishing = furnishing;
    }

    public void setAmenities(List<String> amenities) {
        this.amenities = amenities == null ? new String[0] : amenities.toArray(new String[0]);
    }

    public void setPerks(List<String> perks) {
        this.perks = perks == null ? new String[0] : perks.toArray(new String[0]);
    }

    public void setMoney(Integer monthlyRentInr, Integer depositInr, Integer maintenanceInr,
                         boolean maintenanceIncluded, int brokerageInr, int buyoutInr) {
        this.monthlyRentInr = monthlyRentInr;
        this.depositInr = depositInr;
        this.maintenanceInr = maintenanceInr;
        this.maintenanceIncluded = maintenanceIncluded;
        this.brokerageInr = brokerageInr;
        this.buyoutInr = buyoutInr;
    }

    public void setHandoverTerms(Boolean ownerInformed, DepositPaidTo depositPaidTo,
                                 Boolean registeredAgreement, AgreementHandling agreementHandling,
                                 Short noticePeriodDays, Boolean bachelorsAllowed) {
        this.ownerInformed = ownerInformed;
        this.depositPaidTo = depositPaidTo;
        this.registeredAgreement = registeredAgreement;
        this.agreementHandling = agreementHandling;
        this.noticePeriodDays = noticePeriodDays;
        this.bachelorsAllowed = bachelorsAllowed;
    }

    public void setFlatmateRules(FlatmateGender gender,
                                 String professions, Boolean nonVeg, Boolean pets,
                                 Boolean smoking, Boolean guests, String note) {
        this.flatmateGender = gender;
        this.flatmateProfessions = professions;
        this.nonVegAllowed = nonVeg;
        this.petsAllowed = pets;
        this.smokingAllowed = smoking;
        this.guestsAllowed = guests;
        this.houseRulesNote = note;
    }

    public void setAvailableFrom(LocalDate availableFrom) {
        this.availableFrom = availableFrom;
    }

    // ---- Identity ---------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Listing other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Listing{id=" + id + ", status=" + status + ", area=" + areaName + '}';
    }
}
