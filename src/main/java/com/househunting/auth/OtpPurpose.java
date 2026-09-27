package com.househunting.auth;

/**
 * Why a challenge was issued. Mirrors otp_challenges_purpose_ck.
 *
 * <p>Each purpose carries the {@link OtpChannel} it is delivered on, so no
 * caller ever passes a channel alongside a purpose and no code has to infer one
 * from the shape of the subject. "What is this code for" and "how does it get
 * there" are answered together, once, here.
 */
public enum OtpPurpose {

    /**
     * Signing in by phone, or creating an account that way.
     *
     * <p>Not reachable today: SMS is blocked behind DLT registration. Kept
     * because the path is written and tested and returns unchanged the day that
     * clears — deleting it now would be churn dressed up as tidying.
     */
    LOGIN(OtpChannel.SMS),

    /**
     * Signing in by email, or creating an account. The live login flow.
     *
     * <p>Sign-in and sign-up are one operation, which is also what stops the
     * endpoint being an account-existence oracle: the behaviour is identical
     * whether or not the address is known.
     */
    EMAIL_LOGIN(OtpChannel.EMAIL),

    /** Changing the number on an existing account. Waiting on DLT too. */
    PHONE_CHANGE(OtpChannel.SMS);

    private final OtpChannel channel;

    OtpPurpose(OtpChannel channel) {
        this.channel = channel;
    }

    public OtpChannel channel() {
        return channel;
    }
}
