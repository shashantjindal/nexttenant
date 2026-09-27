package com.nexttenant.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * What the code screen needs to know after a code has been sent.
 *
 * <p>Carries the MASKED subject, never the real one. The screen has to show
 * where the code went — otherwise nobody can tell they mistyped it — but
 * showing the whole address or number hands it to anyone glancing at the
 * screen.
 *
 * @param maskedSubject  e.g. "a•••a@gmail.com" or "+91 ***** 43210"
 * @param channel        how it was delivered, so the page can say "email" or
 *                       "message" without guessing from the shape of the string
 * @param resendCooldown how long before "Resend" becomes available
 * @param expiresAt      when the code stops working
 */
public record OtpDispatch(
        String maskedSubject,
        OtpChannel channel,
        Duration resendCooldown,
        Instant expiresAt
) {
    /** Seconds for the countdown in the design ("Resend in 28"). */
    public long resendSeconds() {
        return resendCooldown.toSeconds();
    }
}
