package com.househunting.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.househunting.common.Hashing;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A user's contact details. <strong>Never rendered on a public page.</strong>
 *
 * <p>Readable only by the owner, or by someone whose interest request against
 * one of the owner's listings has reached {@code CONNECTED}.
 *
 * <p>Note what is <em>not</em> here: any association back to {@link User}. The
 * only link is {@link #userId}, so loading a profile cannot pull a phone
 * number along with it, and reaching one requires a deliberate repository
 * call. The two tables share a primary key, so that call is a cheap lookup —
 * the cost of the split is a line of code, not a query plan.
 *
 * @see User
 */
@Entity
@Table(name = "user_private")
public class UserPrivate {

    /**
     * Indian mobile numbers in E.164: +91 then ten digits starting 6-9.
     *
     * <p>The same rule exists as user_private_phone_format_ck in V2. That is
     * intentional duplication: the constraint is the guarantee, this is the
     * fast, friendly failure. Validating only in Java would leave the database
     * open to bugs and scripts; validating only in the database would turn a
     * user typo into a 500.
     */
    private static final Pattern E164_INDIA = Pattern.compile("^\\+91[6-9][0-9]{9}$");

    /**
     * A shape check, matching user_private_email_ck in V14. Real validation is
     * {@code LoginEmails}, at the edge; this stops a bug writing rubbish.
     */
    private static final Pattern EMAIL_SHAPE =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    /**
     * Shares the primary key of {@code users}. Not generated: this IS the
     * user's id, so the row cannot exist without its user.
     */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * The login identity, normalised by {@code LoginEmails} before it gets
     * here — trimmed and lower-cased, so one address has exactly one
     * representation and {@code Me@Gmail.com} cannot become a second account.
     *
     * <p>Required. An account cannot exist without a verified address, because
     * verifying one is what creates the account.
     */
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    /**
     * Derived from {@link #email}, never set by a caller.
     *
     * <p>Exists for the same reason {@link #phoneHash} does: account lookup and
     * OTP rate limiting run against the hash, so those paths never handle the
     * address itself.
     */
    @Column(name = "email_hash", nullable = false, length = 64)
    private String emailHash;

    /**
     * Normalised to E.164 (+919876543210) so one number has exactly one
     * representation. Without normalisation "9876543210", "+91 98765 43210"
     * and "09876543210" become three accounts for one person, and "one number,
     * one account" quietly stops being true.
     *
     * <p><strong>Nullable since V14.</strong> The account is created the moment
     * an email is verified, which is before the profile step where the number
     * is typed. It is also <em>unverified</em> — nothing has proved the person
     * owns it — so {@link User#isPhoneVerified()} stays false until SMS
     * verification exists. Anything that needs a proven number must ask the
     * badge, not this column.
     */
    @Column(name = "phone_e164", length = 16)
    private String phoneE164;

    /**
     * Derived from {@link #phoneE164}, never set by a caller, so the two can
     * never disagree. Null exactly when the number is.
     */
    @Column(name = "phone_hash", length = 64)
    private String phoneHash;

    /**
     * The verified address. The <em>badge</em> —
     * {@link User#getWorkEmailVerifiedAt()} — is public; this is not.
     */
    @Column(name = "work_email", length = 255)
    private String workEmail;

    @Column(name = "created_at", insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected UserPrivate() {
    }

    /**
     * @param userId the id of the {@link User} this belongs to
     * @param email  an already-normalised, already-verified address
     */
    public UserPrivate(UUID userId, String email) {
        this.userId = Objects.requireNonNull(userId, "userId");
        setEmail(email);
    }

    /**
     * Both contact details at once, for callers that already have them.
     *
     * <p>Only the seeder and the tests are in that position. The real flow
     * learns the address first and the number a screen later, which is why the
     * two-argument constructor is the primary one.
     */
    public UserPrivate(UUID userId, String email, String phoneE164) {
        this(userId, email);
        setPhoneE164(phoneE164);
    }

    /**
     * Changing the address recomputes its hash in the same step, so the pair is
     * updated atomically and cannot drift apart.
     */
    public void setEmail(String email) {
        Objects.requireNonNull(email, "email");
        String normalised = email.trim().toLowerCase(java.util.Locale.ROOT);
        if (!EMAIL_SHAPE.matcher(normalised).matches()) {
            // Deliberately does NOT include the value, for the same reason as
            // the phone check below.
            throw new IllegalArgumentException("email must look like an address");
        }
        this.email = normalised;
        this.emailHash = Hashing.sha256Hex(normalised);
    }

    /**
     * Changing the number recomputes the hash in the same step, so the pair is
     * updated atomically and cannot drift apart.
     *
     * <p>Setting a number does NOT verify it. {@code User.verifyPhone()} is a
     * separate act, performed only when something has actually proved ownership
     * — which today is nothing, so it is never called.
     */
    public void setPhoneE164(String phoneE164) {
        Objects.requireNonNull(phoneE164, "phoneE164");
        if (!E164_INDIA.matcher(phoneE164).matches()) {
            // Deliberately does NOT include the value: exception messages reach
            // logs, and a phone number in a log file is the leak this class
            // exists to prevent.
            throw new IllegalArgumentException(
                    "phone must be a valid Indian mobile in E.164 form (+91XXXXXXXXXX)");
        }
        this.phoneE164 = phoneE164;
        this.phoneHash = Hashing.sha256Hex(phoneE164);
    }

    /**
     * Removes the number, clearing both halves of the pair together.
     *
     * <p>user_private_phone_pair_ck refuses a row with one and not the other,
     * so clearing them separately would fail at flush time rather than here.
     */
    public void clearPhone() {
        this.phoneE164 = null;
        this.phoneHash = null;
    }

    public boolean hasPhone() {
        return phoneE164 != null;
    }

    public void setWorkEmail(String workEmail) {
        this.workEmail = workEmail;
    }

    public void clearWorkEmail() {
        this.workEmail = null;
    }

    // ---- Accessors -------------------------------------------------------

    public UUID getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getEmailHash() {
        return emailHash;
    }

    /** Null until the profile step collects it. Unverified even then. */
    public String getPhoneE164() {
        return phoneE164;
    }

    public String getPhoneHash() {
        return phoneHash;
    }

    public String getWorkEmail() {
        return workEmail;
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
        if (!(o instanceof UserPrivate other)) {
            return false;
        }
        return Objects.equals(userId, other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userId);
    }

    @Override
    public String toString() {
        // Prints the id and nothing else, by design. A toString() that
        // included the number would put it into every log line that ever
        // interpolates this object.
        return "UserPrivate{userId=" + userId + '}';
    }
}
