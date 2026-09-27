package com.nexttenant.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An outstanding one-time passcode.
 *
 * <p>Every rule that decides whether a code is still usable lives here as
 * behaviour — expiry, attempt cap, single use, resend cooldown. Scattering
 * those checks across a service produces the classic bug where one code path
 * forgets one of them, and the forgotten one is always the attempt cap.
 *
 * <p>Note the subject itself is absent: only {@link #subjectHash} is stored, so
 * the database never holds a phone number or an email address for an
 * unauthenticated person. The plaintext lives in the HTTP session for the
 * length of the flow, and is gone the moment the flow ends.
 *
 * <p>"Subject" rather than "phone" because a challenge is a code issued to
 * somebody; whether they are reached by SMS or by email is a delivery detail
 * belonging to {@link OtpPurpose#channel()}, not to the stored row.
 */
@Entity
@Table(name = "otp_challenges")
public class OtpChallenge {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "subject_hash", nullable = false, length = 64, updatable = false)
    private String subjectHash;

    /** HMAC-SHA256 of the code under the server secret. Never the code itself. */
    @Column(name = "code_hmac", nullable = false, length = 64)
    private String codeHmac;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20, updatable = false)
    private OtpPurpose purpose = OtpPurpose.LOGIN;

    @Column(name = "attempts", nullable = false)
    private short attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private short maxAttempts;

    @Column(name = "send_count", nullable = false)
    private short sendCount = 1;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;

    /**
     * Written by the application, NOT left to the column default.
     *
     * <p>otp_challenges_expiry_ck asserts expires_at > created_at. If one came
     * from the database DEFAULT now() and the other from the injected Clock,
     * the constraint would compare two different time sources — and fail
     * whenever they disagree, which in production means whenever the app
     * server and database clocks drift. One owner sets both.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** Required by JPA. Not for application use. */
    protected OtpChallenge() {
    }

    public OtpChallenge(String subjectHash, String codeHmac, OtpPurpose purpose,
                        Instant now, Duration validFor, int maxAttempts) {
        this.subjectHash = Objects.requireNonNull(subjectHash, "subjectHash");
        this.codeHmac = Objects.requireNonNull(codeHmac, "codeHmac");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.createdAt = now;
        this.lastSentAt = now;
        this.expiresAt = now.plus(validFor);
        this.maxAttempts = (short) maxAttempts;
    }

    // ---- Rules ------------------------------------------------------------

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** A code is single use: without this, one intercepted code replays until expiry. */
    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean hasAttemptsLeft() {
        return attempts < maxAttempts;
    }

    public int attemptsRemaining() {
        return Math.max(0, maxAttempts - attempts);
    }

    /**
     * Usable only if all four hold. Expressed as one method so no caller can
     * check three of them and miss the fourth.
     */
    public boolean isUsable(Instant now) {
        return !isConsumed() && !isExpired(now) && hasAttemptsLeft();
    }

    /**
     * Records a guess.
     *
     * <p>Called for wrong AND right answers, before the comparison. Counting
     * only failures would let an attacker interleave a known-good code on
     * their own challenge to keep a counter low; counting every attempt keeps
     * the budget honest.
     */
    public void recordAttempt() {
        this.attempts++;
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }

    /**
     * Burns the remaining budget.
     *
     * <p>Used when the attempt cap is reached, so the challenge is dead
     * immediately rather than lingering until expiry as a target.
     */
    public void exhaust(Instant now) {
        this.attempts = this.maxAttempts;
        this.consumedAt = now;
    }

    public boolean canResend(Instant now, Duration cooldown, int maxSends) {
        return sendCount < maxSends && !now.isBefore(lastSentAt.plus(cooldown));
    }

    public Duration resendAvailableIn(Instant now, Duration cooldown) {
        Duration remaining = Duration.between(now, lastSentAt.plus(cooldown));
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Issues a fresh code on the same challenge.
     *
     * <p>A resend replaces the code rather than adding a second live one.
     * Allowing several valid codes at once multiplies an attacker's chances by
     * the number of resends they can trigger.
     */
    public void resend(String newCodeHmac, Instant now, Duration validFor) {
        this.codeHmac = newCodeHmac;
        this.lastSentAt = now;
        this.sendCount++;
        this.expiresAt = now.plus(validFor);
    }

    // ---- Accessors --------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public String getSubjectHash() {
        return subjectHash;
    }

    public String getCodeHmac() {
        return codeHmac;
    }

    public OtpPurpose getPurpose() {
        return purpose;
    }

    public short getAttempts() {
        return attempts;
    }

    public short getMaxAttempts() {
        return maxAttempts;
    }

    public short getSendCount() {
        return sendCount;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OtpChallenge other)) {
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
        // No hash, no code, no number — this object exists to hold a secret,
        // and toString() output ends up in logs.
        return "OtpChallenge{id=" + id + ", attempts=" + attempts + "/" + maxAttempts + '}';
    }
}
