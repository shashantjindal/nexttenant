package com.househunting.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's PUBLIC profile — everything here may legitimately be rendered to a
 * stranger on a listing page.
 *
 * <p><strong>There is deliberately no association to {@link UserPrivate}.</strong>
 * An {@code @OneToOne} would put {@code user.getPrivate().getPhoneE164()} one
 * autocomplete away from every line in the codebase, and a lazy association
 * would fire that query from wherever it was touched — including during
 * template rendering. Reaching contact details must be a decision, so it
 * requires an explicit repository call instead.
 *
 * @see UserPrivate
 */
@Entity
@Table(name = "users")
public class User {

    /**
     * Assigned in Java rather than by the database default.
     *
     * <p>The id therefore exists before the INSERT, which keeps
     * {@link #equals(Object)} correct for unsaved instances and lets us build
     * an object graph before flushing. The column's {@code DEFAULT
     * gen_random_uuid()} remains as a backstop for direct SQL.
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    /**
     * "Only your first name is ever shown to other people."
     *
     * <p>There is no surname field anywhere in the system: not hidden, absent.
     */
    @Column(name = "first_name", length = 40)
    private String firstName;

    /**
     * STRING, never ORDINAL.
     *
     * <p>ORDINAL persists the enum's declaration position (0, 1, 2). Inserting
     * a new constant in the middle a year later would silently redefine every
     * existing row — no error, no migration, just corrupt data. STRING also
     * matches the CHECK constraint in V2.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 20)
    private Gender gender;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_bracket", length = 20)
    private AgeBracket ageBracket;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    /** Public trust badge. The number itself lives in {@link UserPrivate}. */
    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    /** Public trust badge. The address itself lives in {@link UserPrivate}. */
    @Column(name = "work_email_verified_at")
    private Instant workEmailVerifiedAt;

    /**
     * Owned by the database (DEFAULT now()), not by Java.
     *
     * <p>{@code insertable = false} keeps Hibernate from writing it;
     * {@code @Generated} tells Hibernate to read the value back afterwards, so
     * the in-memory object matches the row.
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    /** Maintained by the set_updated_at() trigger, for the same reason. */
    @Column(name = "updated_at", insertable = false, updatable = false)
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    /**
     * Moderator rights.
     *
     * <p>Granted only by direct database access. There is deliberately no UI to
     * promote a user: the privilege is rare, permanent in effect, and an
     * accidental click should not be able to confer it.
     */
    @Column(name = "is_admin", nullable = false)
    private boolean admin = false;

    @Column(name = "removed_at")
    private Instant removedAt;

    /** Kept even if the account is later reinstated. */
    @Column(name = "removed_reason", length = 200)
    private String removedReason;

    /** Required by JPA. Not for application use. */
    protected User() {
    }

    public User(String firstName, Gender gender, AgeBracket ageBracket) {
        this.firstName = firstName;
        this.gender = gender;
        this.ageBracket = ageBracket;
    }

    // ---- Behaviour -------------------------------------------------------
    // Intent-revealing methods rather than bare setters: the rule for HOW a
    // fact is recorded stays in one place instead of being reinvented by each
    // caller.

    public void verifyPhone() {
        this.phoneVerifiedAt = Instant.now();
    }

    public void verifyWorkEmail() {
        this.workEmailVerifiedAt = Instant.now();
    }

    public void touchLastSeen() {
        this.lastSeenAt = Instant.now();
    }

    public void suspend() {
        this.status = UserStatus.SUSPENDED;
    }

    /** Terminal. Applied after two confirmed reports from two different people. */
    public void remove(Instant now, String reason) {
        this.status = UserStatus.REMOVED;
        this.removedAt = now;
        this.removedReason = reason;
    }

    /**
     * Undoes a removal.
     *
     * <p>removedAt and removedReason are deliberately kept: "this account was
     * removed in March for asking for money" stays true even after the decision
     * is reversed, and a moderator reviewing a later report should see it.
     */
    public void reinstate() {
        this.status = UserStatus.ACTIVE;
    }

    public void updateProfile(String firstName, Gender gender, AgeBracket ageBracket) {
        this.firstName = firstName;
        this.gender = gender;
        this.ageBracket = ageBracket;
    }

    public boolean isPhoneVerified() {
        return phoneVerifiedAt != null;
    }

    public boolean isWorkEmailVerified() {
        return workEmailVerifiedAt != null;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    // ---- Accessors -------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public Gender getGender() {
        return gender;
    }

    public AgeBracket getAgeBracket() {
        return ageBracket;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getPhoneVerifiedAt() {
        return phoneVerifiedAt;
    }

    public Instant getWorkEmailVerifiedAt() {
        return workEmailVerifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public boolean isAdmin() {
        return admin;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public String getRemovedReason() {
        return removedReason;
    }

    // ---- Identity --------------------------------------------------------
    // Compares on id only. Because the id is assigned at construction it is
    // never null, so an instance keeps a stable identity before and after it
    // is persisted — which matters if it is ever put in a HashSet.

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof User other)) {
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
        // Deliberately minimal. An entity toString() that prints every field
        // ends up in log lines, and log lines end up in places we do not
        // control. Nothing sensitive is here, and nothing should be added.
        return "User{id=" + id + ", status=" + status + '}';
    }
}
