package com.househunting.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of an E.164 phone number, as 64 lowercase hex characters.
 *
 * <p>The hash exists so that code paths which only need to ASK ABOUT a number
 * — "have we seen this one?", "how many OTPs has it requested this hour?" —
 * never have to handle the number itself.
 *
 * <p>This is deliberately a plain digest rather than a password hash such as
 * bcrypt. The two have opposite requirements: a password hash must be slow to
 * resist brute force, whereas this must be deterministic so the same number
 * always produces the same lookup key. It provides no protection against an
 * attacker who guesses numbers — the search space is only ten digits — and it
 * is not intended to. Its job is to keep the plaintext out of rate-limit
 * tables and log lines, not to make the number unrecoverable.
 */
final class PhoneHash {

    private PhoneHash() {
    }

    static String of(String phoneE164) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(phoneE164.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM by the Java specification, so
            // this cannot happen on a working runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
