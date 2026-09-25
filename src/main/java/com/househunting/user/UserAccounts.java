package com.househunting.user;

import com.househunting.common.Hashing;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Finding and creating accounts.
 *
 * <p>Sign-in and sign-up are the same operation here: a verified number either
 * already has an account or gets one. There is no separate registration flow,
 * which is why the OTP endpoint cannot be used to discover whether a number is
 * registered — the behaviour is identical either way.
 */
@Service
public class UserAccounts {

    private final UserRepository users;
    private final UserPrivateRepository userPrivates;

    public UserAccounts(UserRepository users, UserPrivateRepository userPrivates) {
        this.users = users;
        this.userPrivates = userPrivates;
    }

    /**
     * Returns the account for a verified number, creating one if needed.
     *
     * <p>Call only after an OTP has been verified: this grants access to an
     * account purely on the strength of the number.
     */
    @Transactional
    public User findOrCreate(String phoneE164) {
        String phoneHash = Hashing.sha256Hex(phoneE164);

        Optional<User> existing = userPrivates.findByPhoneHash(phoneHash)
                .map(UserPrivate::getUserId)
                .flatMap(users::findById);

        if (existing.isPresent()) {
            User user = existing.get();
            user.touchLastSeen();
            return user;
        }

        try {
            return createAccount(phoneE164);
        } catch (DataIntegrityViolationException e) {
            // Two requests verified the same number at once and raced to
            // insert. The UNIQUE constraint on phone_e164 let exactly one win,
            // which is the constraint doing its job rather than a failure.
            // The loser reads the winner's row instead of creating a duplicate.
            return userPrivates.findByPhoneHash(phoneHash)
                    .map(UserPrivate::getUserId)
                    .flatMap(users::findById)
                    .orElseThrow(() -> e);
        }
    }

    private User createAccount(String phoneE164) {
        User user = new User(null, null, null);
        // The number is verified by definition at this point: findOrCreate is
        // only reached after a code was accepted.
        user.verifyPhone();
        users.save(user);

        // The FIRST time this number is written to the database. Until now it
        // existed only in the caller's session.
        userPrivates.saveAndFlush(new UserPrivate(user.getId(), phoneE164));

        return user;
    }

    /** Completes the profile step: the name and details shown to other people. */
    @Transactional
    public User completeProfile(UUID userId, String firstName, Gender gender, AgeBracket ageBracket) {
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("account not found: " + userId));
        user.updateProfile(firstName, gender, ageBracket);
        return user;
    }

    @Transactional(readOnly = true)
    public Optional<User> byId(UUID userId) {
        return users.findById(userId);
    }
}
