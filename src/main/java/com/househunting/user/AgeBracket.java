package com.househunting.user;

/**
 * Brackets rather than a date of birth.
 *
 * <p>A bracket powers the "flatmate age range" filter while being far less
 * identifying than a birthdate. We cannot leak what we never collected.
 *
 * <p>Mirrors the users_age_bracket_ck CHECK constraint.
 */
public enum AgeBracket {
    AGE_18_22,
    AGE_23_27,
    AGE_28_32,
    AGE_33_40,
    AGE_41_PLUS
}
