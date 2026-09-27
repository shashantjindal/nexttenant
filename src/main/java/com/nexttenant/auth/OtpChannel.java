package com.nexttenant.auth;

/**
 * How a code reaches the person it was issued to.
 *
 * <p>Introduced when email became the login identity. Before that the answer
 * was always "SMS", so it did not need saying; now it does, and it needs saying
 * in exactly one place.
 *
 * <p><b>Why an enum rather than a boolean.</b> The channel decides three things
 * at once — which sender delivers, how the subject is masked for display, and
 * which rate-limit bucket the send is counted in. A boolean {@code byEmail}
 * would leave those three decisions scattered as separate {@code if}s, free to
 * disagree. A future WHATSAPP or VOICE case then breaks every switch that has
 * not been updated, which is the compiler doing review for us.
 */
public enum OtpChannel {

    /**
     * A text message.
     *
     * <p>Blocked behind DLT registration in India, so nothing uses this for
     * login today. Kept because the code path is written and tested, and
     * deleting it to re-add it later is churn rather than tidiness.
     */
    SMS(RateLimitScope.PHONE),

    /** Email. The live login channel. */
    EMAIL(RateLimitScope.EMAIL);

    private final RateLimitScope scope;

    OtpChannel(RateLimitScope scope) {
        this.scope = scope;
    }

    /**
     * Which per-subject bucket a send counts against.
     *
     * <p>Deliberately not shared between channels. One address and one number
     * are different subjects, and an email costs nothing like an SMS — so they
     * deserve separate budgets, not a single number that has to be tuned for
     * the more expensive of the two.
     */
    public RateLimitScope rateLimitScope() {
        return scope;
    }

    /**
     * Masks a subject for the "we sent a code to …" line.
     *
     * <p>The screen must name the destination, or somebody who mistyped it has
     * no way to notice. It must not print it in full, because a shoulder-glance
     * or a support screenshot would then carry the whole address or number.
     */
    public String mask(String subject) {
        return switch (this) {
            case SMS -> PhoneNumbers.mask(subject);
            case EMAIL -> LoginEmails.mask(subject);
        };
    }
}
