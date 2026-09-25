package com.househunting.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * What the OTP screen needs to know after a code has been sent.
 *
 * <p>Carries the MASKED number, never the real one. The screen has to show
 * which number the code went to — otherwise nobody can tell they mistyped it —
 * but showing all ten digits hands the number to anyone glancing at the screen.
 *
 * @param maskedPhone     e.g. "+91 ***** 43210"
 * @param resendCooldown  how long before "Resend OTP" becomes available
 * @param expiresAt       when the code stops working
 */
public record OtpDispatch(
        String maskedPhone,
        Duration resendCooldown,
        Instant expiresAt
) {
    /** Seconds for the countdown in the design ("Resend in 28"). */
    public long resendSeconds() {
        return resendCooldown.toSeconds();
    }
}
