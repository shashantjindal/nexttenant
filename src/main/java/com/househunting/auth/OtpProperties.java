package com.househunting.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Tunable policy for one-time passcodes, bound from {@code househunting.otp.*}.
 *
 * <p>{@code @ConfigurationProperties} over scattered {@code @Value}
 * annotations: the settings are one typed object that can be injected into a
 * test, Durations are parsed by Spring rather than by hand, and a typo in a
 * property name fails at startup instead of silently leaving a default in
 * place.
 *
 * @param secret         HMAC key for hashing codes. MUST be overridden outside dev.
 * @param codeValidity   how long a code remains usable
 * @param resendCooldown minimum gap between sends of one challenge
 * @param maxAttempts    guesses allowed per challenge before it is burned
 * @param maxSends       how many times one challenge may be (re)sent
 * @param perPhonePerHour  codes per number per window
 * @param perIpPerHour     codes per IP per window
 * @param rateWindow     the counting window
 */
@ConfigurationProperties(prefix = "househunting.otp")
public record OtpProperties(
        String secret,
        Duration codeValidity,
        Duration resendCooldown,
        int maxAttempts,
        int maxSends,
        int perPhonePerHour,
        int perIpPerHour,
        Duration rateWindow
) {

    /**
     * The value shipped in application.yml. Present so a clone runs without
     * setup; {@link OtpSecretCheck} refuses to start with it outside dev.
     */
    public static final String DEV_SECRET = "dev-only-insecure-otp-secret-change-me";

    public OtpProperties {
        // Compact constructor: fills defaults so a partial configuration block
        // is valid, and callers never receive a null Duration.
        codeValidity = codeValidity != null ? codeValidity : Duration.ofMinutes(10);
        resendCooldown = resendCooldown != null ? resendCooldown : Duration.ofSeconds(30);
        rateWindow = rateWindow != null ? rateWindow : Duration.ofHours(1);
        maxAttempts = maxAttempts > 0 ? maxAttempts : 5;
        maxSends = maxSends > 0 ? maxSends : 5;
        perPhonePerHour = perPhonePerHour > 0 ? perPhonePerHour : 5;
        perIpPerHour = perIpPerHour > 0 ? perIpPerHour : 20;
        secret = secret != null && !secret.isBlank() ? secret : DEV_SECRET;
    }

    public byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean usingDevSecret() {
        return DEV_SECRET.equals(secret);
    }
}
