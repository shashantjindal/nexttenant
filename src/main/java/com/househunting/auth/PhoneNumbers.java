package com.househunting.auth;

import java.util.regex.Pattern;

/**
 * Normalises however a person typed their number into one canonical E.164
 * string.
 *
 * <p>This is what makes "one number, one account" true. All of these are the
 * same phone:
 *
 * <pre>
 *   9876543210        98765 43210      +91 98765-43210
 *   09876543210       919876543210     +91-9876543210
 * </pre>
 *
 * <p>Without normalisation they are six accounts, the uniqueness constraint on
 * {@code user_private.phone_e164} never fires, and a banned user simply signs
 * up again by adding a space.
 *
 * <p>Deliberately not libphonenumber: that library is excellent and large, and
 * we serve exactly one country with a strictly defined mobile format. Adding a
 * dependency to solve a problem a regex solves correctly is a cost without a
 * return. If HouseHunting ever crosses a border, that decision flips.
 */
public final class PhoneNumbers {

    /** Indian mobiles: ten digits, first digit 6-9. Landlines are not valid here. */
    private static final Pattern TEN_DIGIT_MOBILE = Pattern.compile("^[6-9][0-9]{9}$");

    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");

    private PhoneNumbers() {
    }

    /**
     * @return the number as {@code +91XXXXXXXXXX}
     * @throws InvalidPhoneNumberException if it is not a valid Indian mobile
     */
    public static String normalise(String input) {
        if (input == null || input.isBlank()) {
            throw new InvalidPhoneNumberException("Enter your mobile number");
        }

        // Strip spaces, hyphens, brackets and a leading plus. What remains is
        // digits, which is the only part that carries meaning.
        String digits = NON_DIGITS.matcher(input).replaceAll("");

        // Shed the country code or trunk prefix if present, leaving ten digits.
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);          // 919876543210
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);          // 09876543210
        } else if (digits.length() == 13 && digits.startsWith("091")) {
            digits = digits.substring(3);          // 0919876543210
        }

        if (!TEN_DIGIT_MOBILE.matcher(digits).matches()) {
            // The message never echoes the input. Validation errors are
            // rendered into pages and written to logs, and a phone number in
            // either is a leak.
            throw new InvalidPhoneNumberException(
                    "That does not look like an Indian mobile number");
        }

        return "+91" + digits;
    }

    /**
     * Masks a number for display: {@code +91 98765 43210} becomes
     * {@code +91 ***** 43210}.
     *
     * <p>The OTP screen has to show which number the code went to, or people
     * cannot tell they mistyped it. Showing the whole number means anyone
     * glancing at the screen gets it, so the design shows enough to recognise
     * and not enough to use.
     */
    public static String mask(String e164) {
        if (e164 == null || e164.length() != 13) {
            return "";
        }
        return "+91 ***** " + e164.substring(8);
    }
}
