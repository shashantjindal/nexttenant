package com.househunting.auth;

import java.security.SecureRandom;

/**
 * Generates one-time passcodes.
 */
public final class OtpCodes {

    /**
     * SecureRandom, never java.util.Random.
     *
     * <p>java.util.Random is a linear congruential generator: given a couple of
     * consecutive outputs its internal state can be solved for, after which
     * every future value is predictable. For a dice roll that is irrelevant.
     * For a login code it means an attacker who requests two OTPs on their own
     * number can compute the next one issued to anybody.
     *
     * <p>SecureRandom draws from the operating system's entropy pool and is
     * thread-safe, so one shared instance is correct.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Six digits, as the design's six input boxes require. */
    public static final int LENGTH = 6;

    private static final int BOUND = 1_000_000;

    private OtpCodes() {
    }

    /**
     * @return six digits, zero-padded, so "000123" is a legal code
     */
    public static String generate() {
        // %06d matters: without it nextInt would produce "123" one time in a
        // thousand, and a code that is sometimes shorter breaks the six-box
        // input and hands an observer a shorter search space.
        return String.format("%06d", RANDOM.nextInt(BOUND));
    }

    /** Accepts only six digits. Anything else is rejected before any lookup. */
    public static boolean isWellFormed(String code) {
        if (code == null || code.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            if (code.charAt(i) < '0' || code.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
