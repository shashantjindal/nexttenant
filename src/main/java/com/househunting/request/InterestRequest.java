package com.househunting.request;

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
 * Someone asking a leaver for their contact details.
 *
 * <p><strong>This row is the sole authority for contact release.</strong>
 * There is deliberately no separate "grants" table: the release is symmetric —
 * accepting reveals each side's number to the other — so one row expresses it
 * completely. A second table would be derived state that can disagree with its
 * source, and disagreement about who may see a phone number is the worst
 * possible kind of bug.
 *
 * <p>The transitions are deliberately narrow. Only the leaver may accept or
 * decline; only the requester may withdraw; either party or moderation may
 * revoke. Nothing moves backwards out of a terminal state, so a declined
 * request cannot quietly become connected later.
 */
@Entity
@Table(name = "interest_requests")
public class InterestRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Column(name = "requester_user_id", nullable = false, updatable = false)
    private UUID requesterUserId;

    /** "Leavers read maybe fifteen of these a week — specifics win." */
    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(name = "created_at", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    /**
     * When contact was released.
     *
     * <p>interest_requests_connected_ck requires this whenever the status is
     * CONNECTED, so "when did these two get each other's numbers?" is always
     * answerable — which is the first question asked after an incident.
     */
    @Column(name = "connected_at")
    private Instant connectedAt;

    /**
     * When the REQUESTER last saw the answer to this.
     *
     * <p>Null means unread news. Only the requester needs such a marker: the
     * leaver reads "still needs me" straight off {@link #status}, because a
     * request they have dealt with is no longer PENDING. Nothing moves the
     * requester's side out of CONNECTED, so without this column their badge
     * would light up once and stay lit forever.
     */
    @Column(name = "requester_seen_at")
    private Instant requesterSeenAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    protected InterestRequest() {
    }

    public InterestRequest(UUID listingId, UUID requesterUserId, String note) {
        this.listingId = Objects.requireNonNull(listingId, "listingId");
        this.requesterUserId = Objects.requireNonNull(requesterUserId, "requesterUserId");
        this.note = note;
    }

    // ---- Transitions ------------------------------------------------------

    /**
     * The leaver accepts. This is the moment contact is released.
     *
     * <p>Only from PENDING. Re-accepting a declined or withdrawn request would
     * let a leaver change their mind about someone who had already been told
     * no — or worse, let a stale form resubmission resurrect a decision.
     */
    public void accept(Instant now) {
        requireStatus(RequestStatus.PENDING, "accept");
        this.status = RequestStatus.CONNECTED;
        this.respondedAt = now;
        this.connectedAt = now;
    }

    public void decline(Instant now) {
        requireStatus(RequestStatus.PENDING, "decline");
        this.status = RequestStatus.DECLINED;
        this.respondedAt = now;
    }

    /** The requester changes their mind before an answer. */
    public void withdraw(Instant now) {
        requireStatus(RequestStatus.PENDING, "withdraw");
        this.status = RequestStatus.WITHDRAWN;
        this.respondedAt = now;
    }

    /**
     * Removes access after a connection.
     *
     * <p>connected_at is deliberately left in place: the connection happened,
     * and erasing that would make the record lie about the past. Contact stops
     * being readable because the status no longer releases it, not because the
     * history was rewritten.
     */
    public void revoke(Instant now) {
        if (status != RequestStatus.CONNECTED) {
            throw new IllegalStateException("Only a connected request can be revoked");
        }
        this.status = RequestStatus.REVOKED;
        this.respondedAt = now;
    }

    private void requireStatus(RequestStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Cannot %s a request that is %s".formatted(action, status));
        }
    }

    // ---- Queries ----------------------------------------------------------

    /** The single condition under which either party may read the other's number. */
    public boolean releasesContact() {
        return status.releasesContact();
    }

    public boolean wasSentBy(UUID userId) {
        return requesterUserId.equals(userId);
    }

    public boolean isForListing(UUID id) {
        return listingId.equals(id);
    }

    /**
     * Has the leaver answered this, one way or the other?
     *
     * <p>WITHDRAWN is excluded because the requester did that themselves, and
     * REVOKED because it is not an answer to the original ask. Both would be
     * news about something the person already knows.
     */
    public boolean isAnswered() {
        return status == RequestStatus.CONNECTED || status == RequestStatus.DECLINED;
    }

    /** Answered, and the person who asked has not looked yet. */
    public boolean isUnseenByRequester() {
        return isAnswered() && requesterSeenAt == null;
    }

    /**
     * Marks the answer read.
     *
     * <p>Idempotent, and deliberately only moves null to a value: re-stamping
     * on every page load would make "when did they first see this?" — the one
     * question the column can answer — unanswerable.
     */
    public void markSeenByRequester(Instant now) {
        if (requesterSeenAt == null) {
            this.requesterSeenAt = now;
        }
    }

    // ---- Accessors --------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getListingId() {
        return listingId;
    }

    public UUID getRequesterUserId() {
        return requesterUserId;
    }

    public String getNote() {
        return note;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRequesterSeenAt() {
        return requesterSeenAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InterestRequest other)) {
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
        // No note: it is free text a person wrote, and toString() output lands
        // in logs.
        return "InterestRequest{id=" + id + ", status=" + status + '}';
    }
}
