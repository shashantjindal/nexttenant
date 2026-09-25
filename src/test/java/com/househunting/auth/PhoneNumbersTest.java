package com.househunting.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNumbersTest {

    @ParameterizedTest
    @DisplayName("every way a person might type one number normalises to the same string")
    @ValueSource(strings = {
            "9876543210",
            "09876543210",
            "919876543210",
            "+919876543210",
            "+91 98765 43210",
            "+91-9876-543-210",
            "  9876543210  ",
            "(+91) 98765 43210",
            "0919876543210"
    })
    void normalisesToE164(String input) {
        // This is what makes "one number, one account" true. Without it these
        // are nine accounts, the UNIQUE constraint never fires, and a removed
        // user signs up again by adding a space.
        assertThat(PhoneNumbers.normalise(input)).isEqualTo("+919876543210");
    }

    @ParameterizedTest
    @DisplayName("non-mobile and malformed inputs are rejected")
    @ValueSource(strings = {
            "5876543210",        // Indian mobiles never start 0-5
            "1234567890",
            "98765",             // too short
            "98765432109",       // too long
            "+14155552671",      // not Indian
            "abcdefghij",
            "+91"
    })
    void rejectsInvalid(String input) {
        assertThatThrownBy(() -> PhoneNumbers.normalise(input))
                .isInstanceOf(InvalidPhoneNumberException.class);
    }

    @Test
    @DisplayName("blank input is rejected with a usable message")
    void rejectsBlank() {
        assertThatThrownBy(() -> PhoneNumbers.normalise(null))
                .isInstanceOf(InvalidPhoneNumberException.class);
        assertThatThrownBy(() -> PhoneNumbers.normalise("   "))
                .isInstanceOf(InvalidPhoneNumberException.class);
    }

    @Test
    @DisplayName("error messages never echo the number back")
    void errorMessagesOmitTheNumber() {
        // Validation errors are rendered into pages and written to logs, and a
        // phone number in either is a leak.
        assertThatThrownBy(() -> PhoneNumbers.normalise("5876543210"))
                .hasMessageNotContaining("5876543210");
    }

    @Test
    @DisplayName("masking shows enough to recognise, not enough to use")
    void masksForDisplay() {
        assertThat(PhoneNumbers.mask("+919876543210")).isEqualTo("+91 ***** 43210");
        assertThat(PhoneNumbers.mask("+919876543210")).doesNotContain("98765");
        assertThat(PhoneNumbers.mask(null)).isEmpty();
    }

    @Test
    @DisplayName("generated codes are always six digits, including leading zeros")
    void codesAreAlwaysSixDigits() {
        // %06d matters: without it, one code in a thousand would be shorter,
        // breaking the six-box input and shrinking the search space.
        for (int i = 0; i < 2000; i++) {
            String code = OtpCodes.generate();
            assertThat(code).hasSize(6).matches("^[0-9]{6}$");
            assertThat(OtpCodes.isWellFormed(code)).isTrue();
        }
    }

    @Test
    @DisplayName("only six-digit strings are well-formed")
    void rejectsMalformedCodes() {
        assertThat(OtpCodes.isWellFormed("123456")).isTrue();
        assertThat(OtpCodes.isWellFormed("000000")).isTrue();
        assertThat(OtpCodes.isWellFormed("12345")).isFalse();
        assertThat(OtpCodes.isWellFormed("1234567")).isFalse();
        assertThat(OtpCodes.isWellFormed("12345a")).isFalse();
        assertThat(OtpCodes.isWellFormed(null)).isFalse();
    }
}
