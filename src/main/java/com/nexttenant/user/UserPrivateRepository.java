package com.nexttenant.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Contact details. Every method here returns private data, so a call to this
 * repository should be visible and justified at the call site.
 *
 * <p>Kept separate from {@link UserRepository} so that reaching a phone number
 * is an explicit act rather than a field access on an object someone already
 * had.
 */
@Repository
public interface UserPrivateRepository extends JpaRepository<UserPrivate, UUID> {

    /**
     * Look an account up by the SHA-256 of its number rather than the number.
     *
     * <p>Sign-in and OTP rate limiting only need to know WHICH account a number
     * belongs to, not what the number is. Taking a hash keeps the plaintext out
     * of those code paths, out of query logs and out of stack traces.
     */
    Optional<UserPrivate> findByPhoneHash(String phoneHash);

    /** Existence check for "is this number already registered?". */
    boolean existsByPhoneHash(String phoneHash);

    /**
     * Look an account up by the SHA-256 of its login address.
     *
     * <p>The same reasoning as {@link #findByPhoneHash}: sign-in needs to know
     * WHICH account an address belongs to, not what the address is, so the
     * plaintext stays out of query logs and stack traces.
     *
     * <p>Addresses are normalised before hashing, so this is inherently
     * case-insensitive — there is no {@code lower()} to forget.
     */
    Optional<UserPrivate> findByEmailHash(String emailHash);

    /**
     * Is this mobile number already on another account?
     *
     * <p>Asked before the profile step saves one, because the number is
     * UNIQUE and unverified. Returns only a boolean and never the owner: a
     * method that handed back whose account it is would be a pleasant way to
     * look people up by phone number.
     */
    boolean existsByPhoneHashAndUserIdNot(String phoneHash, UUID userId);

    /**
     * Is this work address already claimed, by anyone but the given account?
     *
     * <p>Returns nothing about WHOSE account it is, deliberately. The caller
     * only ever needs the boolean, and a method that handed back the owner
     * would be an inviting way to answer "which of my colleagues is on this
     * site?".
     *
     * <p>Addresses are stored already lowercased, so this is a plain equality
     * match against the same form the unique index uses.
     */
    boolean existsByWorkEmailAndUserIdNot(String workEmail, UUID userId);
}
