package com.househunting.user;

/**
 * Mirrors the users_gender_ck CHECK constraint.
 *
 * <p>Persisted with {@code @Enumerated(EnumType.STRING)}, so the name written
 * here must match the constraint exactly. Renaming a constant is therefore a
 * schema change and needs a migration.
 */
public enum Gender {
    MALE,
    FEMALE,
    OTHER,
    UNDISCLOSED
}
