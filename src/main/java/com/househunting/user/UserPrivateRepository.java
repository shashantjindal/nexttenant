package com.househunting.user;

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
}
