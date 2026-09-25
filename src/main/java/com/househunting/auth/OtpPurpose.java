package com.househunting.auth;

/** Why a challenge was issued. Mirrors otp_challenges_purpose_ck. */
public enum OtpPurpose {
    /** Signing in, or creating an account — the same flow here. */
    LOGIN,
    /** Changing the number on an existing account. */
    PHONE_CHANGE
}
