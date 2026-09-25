package com.househunting.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The two hash functions this application uses, and the rule for choosing
 * between them.
 *
 * <p><strong>{@link #sha256Hex} — for lookup keys.</strong> Deterministic and
 * fast. Used where code needs to ask "which record is this?" without handling
 * the value itself: phone numbers in rate-limit tables, IP addresses in
 * counters. It offers no protection against an attacker who can guess the
 * input, and is not intended to; its job is keeping plaintext out of tables and
 * log lines.
 *
 * <p><strong>{@link #hmacSha256Hex} — for secrets that must not be recovered.</strong>
 * Same speed, but keyed. Used for OTP codes.
 *
 * <p>The distinction matters more than it looks. "Hash the OTP" is the usual
 * advice and, for a six-digit code, achieves close to nothing: there are only a
 * million possible codes, so an attacker holding a leaked SHA-256 digest can
 * try every one of them in milliseconds. Hashing only protects a value the
 * attacker cannot enumerate. HMAC restores the protection by mixing in a
 * server-held secret — without it, those million candidate digests cannot be
 * computed at all.
 *
 * <p>Neither is a password hash. Passwords need a deliberately slow function
 * (bcrypt, argon2); these need to be fast because they run on every request.
 */
public final class Hashing {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private Hashing() {
    }

    /** SHA-256 as 64 lowercase hex characters. For lookup keys, never for secrets. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Required of every JVM by the Java specification.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** HMAC-SHA256 as 64 lowercase hex characters. For values that must not be recoverable. */
    public static String hmacSha256Hex(String value, byte[] secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /**
     * Compares two hex digests in constant time.
     *
     * <p>{@code String.equals} returns as soon as it finds a differing
     * character, so the time it takes leaks how many leading characters
     * matched. An attacker who can measure that precisely can recover a secret
     * one character at a time instead of guessing it whole.
     *
     * <p>Over the public internet, against a 6-digit code already protected by
     * an attempt cap, that attack is not practical. It is used anyway because
     * it costs nothing, and because "not exploitable today" is a poor reason to
     * write the version that is exploitable tomorrow.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
