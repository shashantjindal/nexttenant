package com.househunting.moderation;

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
 * A report against a listing.
 *
 * <p>Reports are anonymous to the person reported: the reporter's id is stored
 * so the same person cannot report twice, but it is never shown to the listing
 * owner. Somebody who reports a broker should not have to worry about who finds
 * out.
 *
 * <p>The two-strikes rule the design states — "Two confirmed reports and the
 * account is gone" — rests on two things. {@code reports_uq} from V4 means one
 * report per person per listing, so two reports are necessarily two people. And
 * only a CONFIRMED report counts, so a human has agreed before an account is
 * removed.
 */
@Entity
@Table(name = "reports")
public class Report {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    /** Stored to prevent duplicates. Never shown to the person reported. */
    @Column(name = "reporter_user_id", nullable = false, updatable = false)
    private UUID reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40, updatable = false)
    private ReportReason reason;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status = ReportStatus.OPEN;

    @Column(name = "created_at", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /**
     * Which moderator decided.
     *
     * <p>reports_reviewed_ck requires both this and reviewed_at on any report
     * that is not OPEN, so a decision always has a name and a time attached to
     * it. A disputed removal with no record of who made it is one person's word
     * against another's.
     */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    protected Report() {
    }

    public Report(UUID listingId, UUID reporterUserId, ReportReason reason, String detail) {
        this.listingId = Objects.requireNonNull(listingId, "listingId");
        this.reporterUserId = Objects.requireNonNull(reporterUserId, "reporterUserId");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.detail = detail;
    }

    public void confirm(Instant now, UUID moderatorId) {
        requireOpen("confirm");
        this.status = ReportStatus.CONFIRMED;
        this.reviewedAt = now;
        this.reviewedBy = Objects.requireNonNull(moderatorId, "moderatorId");
    }

    public void dismiss(Instant now, UUID moderatorId) {
        requireOpen("dismiss");
        this.status = ReportStatus.DISMISSED;
        this.reviewedAt = now;
        this.reviewedBy = Objects.requireNonNull(moderatorId, "moderatorId");
    }

    /**
     * A decided report stays decided.
     *
     * <p>Without this, a second click on a moderation button — or a refresh of
     * the queue — could re-confirm a report and count a second strike against
     * an account for a single incident.
     */
    private void requireOpen(String action) {
        if (status != ReportStatus.OPEN) {
            throw new IllegalStateException(
                    "Cannot %s a report that is already %s".formatted(action, status));
        }
    }

    public boolean isOpen() {
        return status == ReportStatus.OPEN;
    }

    public UUID getId() {
        return id;
    }

    public UUID getListingId() {
        return listingId;
    }

    public UUID getReporterUserId() {
        return reporterUserId;
    }

    public ReportReason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Report other)) {
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
        // No detail and no reporter: the detail is free text somebody wrote in
        // confidence, and toString() output lands in logs.
        return "Report{id=" + id + ", reason=" + reason + ", status=" + status + '}';
    }
}
