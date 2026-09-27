package com.nexttenant.user;

import com.nexttenant.common.Hashing;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Finding and creating accounts.
 *
 * <p>Sign-in and sign-up are the same operation here: a verified email address
 * either already has an account or gets one. There is no separate registration
 * flow, which is why the OTP endpoint cannot be used to discover whether an
 * address is registered — the behaviour is identical either way.
 *
 * <p><b>Identity is the email, contact is the phone.</b> Until V14 those were
 * one thing, because one SMS proved both at once. They are now separate facts
 * acquired at separate moments, and this class is where that shows: an account
 * exists as soon as an address is verified, and the number arrives a screen
 * later, unproven.
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
     * Returns the account for a verified address, creating one if needed.
     *
     * <p>Call only after an OTP has been verified: this grants access to an
     * account purely on the strength of the address.
     *
     * @param email already normalised by {@code LoginEmails.validate}
     */
    @Transactional
    public User findOrCreate(String email) {
        String emailHash = Hashing.sha256Hex(email);

        Optional<User> existing = userPrivates.findByEmailHash(emailHash)
                .map(UserPrivate::getUserId)
                .flatMap(users::findById);

        if (existing.isPresent()) {
            User user = existing.get();
            user.touchLastSeen();
            return user;
        }

        try {
            return createAccount(email);
        } catch (DataIntegrityViolationException e) {
            // Two requests verified the same address at once and raced to
            // insert. The UNIQUE constraint on email_hash let exactly one win,
            // which is the constraint doing its job rather than a failure.
            // The loser reads the winner's row instead of creating a duplicate.
            return userPrivates.findByEmailHash(emailHash)
                    .map(UserPrivate::getUserId)
                    .flatMap(users::findById)
                    .orElseThrow(() -> e);
        }
    }

    private User createAccount(String email) {
        User user = new User(null, null, null);

        // NOTE what is absent: verifyPhone(). Until V14 this method called it,
        // because the OTP that created the account had just proved ownership of
        // a number. That is no longer true — the number is typed on the next
        // screen and nothing has proved it. Calling it here would make
        // users.phone_verified_at a badge that means nothing, and a badge that
        // lies is worse than no badge.
        users.save(user);

        // The FIRST time this address is written to the database. Until now it
        // existed only in the caller's session.
        userPrivates.saveAndFlush(new UserPrivate(user.getId(), email));

        return user;
    }

    /**
     * Completes the profile step: the name shown to other people, and the
     * mobile number they will eventually be reached on.
     *
     * <p><b>Why the number is checked rather than caught.</b> A
     * {@code DataIntegrityViolationException} from the UNIQUE index would
     * arrive too late: catching it inside {@code @Transactional} does not clear
     * the rollback-only mark, so the commit then fails with
     * {@code UnexpectedRollbackException} and the friendly message never
     * reaches the form. The pre-check races in theory; the constraint is still
     * there to win that race, and losing it is a 500 on a one-in-a-million
     * collision rather than a 500 on every collision.
     *
     * @param phoneE164 already normalised; may be null if not collected
     * @throws PhoneAlreadyInUseException if another account holds that number
     */
    @Transactional
    public User completeProfile(UUID userId, String firstName, Gender gender,
                                AgeBracket ageBracket, String phoneE164) {
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("account not found: " + userId));
        user.updateProfile(firstName, gender, ageBracket);

        if (phoneE164 != null) {
            UserPrivate priv = userPrivates.findById(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "account has no contact row: " + userId));

            if (userPrivates.existsByPhoneHashAndUserIdNot(
                    Hashing.sha256Hex(phoneE164), userId)) {
                throw new PhoneAlreadyInUseException(
                        "That number is already on another account.");
            }
            priv.setPhoneE164(phoneE164);
        }

        return user;
    }

    @Transactional(readOnly = true)
    public Optional<User> byId(UUID userId) {
        return users.findById(userId);
    }
}
