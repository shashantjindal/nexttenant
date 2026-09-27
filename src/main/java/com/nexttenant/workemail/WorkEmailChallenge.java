package com.nexttenant.workemail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An outstanding work-email verification code.
 *
 * <p>Every rule deciding whether a code is still usable lives here as
 * behaviour — expiry, attempt cap, single use, resend cooldown. Scattering
 * those across a service produces the classic bug where one code path forgets
 * one of them, and the forgotten one is always the attempt cap.
 *
 * <p>Note the address is absent: only {@link #emailHash} is stored, so the
 * database never holds an unverified claim about where somebody works. The
 * plaintext lives in the HTTP session for the length of the flow.
 *
 * <p>A near-twin of {@code OtpChallenge}. The duplication is deliberate and the
 * alternative was worse: generalising both onto a "destination" column would
 * mean one table where a row's meaning depends on a discriminator, and one set
 * of rate limits shared between "somebody is spamming a phone" and "somebody is
 * spamming a mailbox" — two different problems with two different budgets.
 */
@Entity
@Table(name = "work_email_challenges")
public class WorkEmailChallenge {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "email_hash", nullable = false, length = 64)
    private String emailHash;

    /** HMAC-SHA256 of the code under the server secret. Never the code itself. */
    @Column(name = "code_hmac", nullable = false, length = 64)
    private String codeHmac;

    @Column(name = "attempts", nullable = false)
    private short attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private short maxAttempts;

    @Column(name = "send_count", nullable = false)
    private short sendCount = 1;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;

    /**
     * Written by the application, NOT left to a column default — and the
     * migration has no default for exactly this reason. The CHECK compares
     * created_at with expires_at; sourcing one from the database and the other
     * from the injected Clock means the constraint fails whenever the two
     * machines' clocks drift.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** Required by JPA. Not for application use. */
    protected WorkEmailChallenge() {
    }

    public WorkEmailChallenge(UUID userId, String emailHash, String codeHmac,
                              Instant now, Duration validFor, int maxAttempts) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.emailHash = Objects.requireNonNull(emailHash, "emailHash");
        this.codeHmac = Objects.requireNonNull(codeHmac, "codeHmac");
        this.createdAt = now;
        this.lastSentAt = now;
        this.expiresAt = now.plus(validFor);
        this.maxAttempts = (short) maxAttempts;
    }

    // ---- Rules ------------------------------------------------------------

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean hasAttemptsLeft() {
        return attempts < maxAttempts;
    }

    public int attemptsRemaining() {
        return Math.max(0, maxAttempts - attempts);
    }

    /** All four, as one method, so no caller can check three and miss the fourth. */
    public boolean isUsable(Instant now) {
        return !isConsumed() && !isExpired(now) && hasAttemptsLeft();
    }

    /**
     * Records a guess — for wrong AND right answers, before the comparison.
     * Counting only failures lets an attacker keep a budget alive by
     * interleaving a code they already know.
     */
    public void recordAttempt() {
        this.attempts++;
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }

    /** Burns the budget so a dead challenge is not left lying around as a target. */
    public void exhaust(Instant now) {
        this.attempts = this.maxAttempts;
        this.consumedAt = now;
    }

    public boolean canResend(Instant now, Duration cooldown, int maxSends) {
        return sendCount < maxSends && !now.isBefore(lastSentAt.plus(cooldown));
    }

    /**
     * Issues a fresh code on the same challenge.
     *
     * <p>A resend REPLACES the code rather than adding a second live one.
     * Allowing several valid codes at once multiplies an attacker's chances by
     * however many resends they can trigger.
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

    public UUID getUserId() {
        return userId;
    }

    public String getEmailHash() {
        return emailHash;
    }

    public String getCodeHmac() {
        return codeHmac;
    }

    public short getAttempts() {
        return attempts;
    }

    public short getSendCount() {
        return sendCount;
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
}
