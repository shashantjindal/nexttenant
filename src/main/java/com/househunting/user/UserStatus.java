package com.househunting.user;

/**
 * Account lifecycle. Mirrors the users_status_ck CHECK constraint.
 */
public enum UserStatus {
    /** Normal account. */
    ACTIVE,

    /** Listings hidden pending moderation review; the account can still log in. */
    SUSPENDED,

    /** Terminal state after two confirmed reports from two different people. */
    REMOVED
}
