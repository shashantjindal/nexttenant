package com.househunting.workemail;

import com.househunting.auth.OtpCodes;
import com.househunting.auth.OtpProperties;
import com.househunting.common.Hashing;
import com.househunting.mail.EmailSender;
import com.househunting.user.User;
import com.househunting.user.UserPrivate;
import com.househunting.user.UserPrivateRepository;
import com.househunting.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Issuing and checking work-email verification codes.
 *
 * <p>Deliberately built on the same primitives as the phone OTP —
 * {@link OtpCodes} for generation, {@link Hashing} for the HMAC,
 * {@link OtpProperties} for the server secret and the windows. Two independent
 * implementations of "issue a short code and check it" is two places for the
 * same class of mistake, and only one of them would get the fix.
 *
 * <p>The windows are longer than the phone flow's. An SMS arrives in seconds
 * and people are holding the handset; a work email may sit behind a corporate
 * filter and be read on a different machine twenty minutes later.
 */
@Service
public class WorkEmailService {

    /** Longer than the SMS window. Corporate mail is not instant mail. */
    public static final Duration CODE_VALIDITY = Duration.ofMinutes(30);

    /** Long enough that "resend" is not the first thing somebody reaches for. */
    public static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);

    public static final int MAX_ATTEMPTS = 5;
    public static final int MAX_SENDS = 4;

    private static final String SUBJECT = "Your HouseHunting verification code";

    private final WorkEmailChallengeRepository challenges;
    private final UserRepository users;
    private final UserPrivateRepository userPrivates;
    private final EmailSender email;
    private final OtpProperties config;
    private final Clock clock;

    public WorkEmailService(WorkEmailChallengeRepository challenges,
                            UserRepository users,
                            UserPrivateRepository userPrivates,
                            EmailSender email,
                            OtpProperties config,
                            Clock clock) {
        this.challenges = challenges;
        this.users = users;
        this.userPrivates = userPrivates;
        this.email = email;
        this.config = config;
        this.clock = clock;
    }

    // ---- Issuing ----------------------------------------------------------

    /**
     * Validates the address and sends a code to it.
     *
     * <p>Note what is NOT checked here: whether somebody else has already
     * verified this address. That check happens at the final step instead.
     * Refusing up front would turn this form into an oracle — type an address,
     * learn whether that person is on the site — and the addresses in question
     * are other people's colleagues.
     *
     * @return the masked address, for the "we sent a code to…" line
     * @throws InvalidWorkEmailException if the address is malformed or free
     * @throws ResendTooSoonException    if a code went out moments ago
     */
    @Transactional
    public String requestCode(UUID userId, String rawAddress) {
        String address = WorkEmailAddresses.validate(rawAddress);
        Instant now = clock.instant();

        String emailHash = Hashing.sha256Hex(address);
        String code = OtpCodes.generate();
        String codeHmac = Hashing.hmacSha256Hex(code, config.secretBytes());

        Optional<WorkEmailChallenge> existing =
                challenges.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(userId);

        // Same address, still live: reuse the row and replace the code, rather
        // than leaving two valid codes outstanding.
        if (existing.isPresent()
                && existing.get().getEmailHash().equals(emailHash)
                && !existing.get().isExpired(now)) {

            WorkEmailChallenge challenge = existing.get();
            if (!challenge.canResend(now, RESEND_COOLDOWN, MAX_SENDS)) {
                throw new ResendTooSoonException(
                        "A code has just gone out. Give it a minute before asking for another.");
            }
            challenge.resend(codeHmac, now, CODE_VALIDITY);
            challenges.save(challenge);
        } else {
            // A different address, or the old one has lapsed. Consume the old
            // challenge so it cannot be answered afterwards: otherwise
            // switching address twice leaves a trail of usable codes.
            existing.ifPresent(old -> {
                old.consume(now);
                challenges.save(old);
            });
            challenges.save(new WorkEmailChallenge(
                    userId, emailHash, codeHmac, now, CODE_VALIDITY, MAX_ATTEMPTS));
        }

        email.send(address, SUBJECT, body(code));
        return WorkEmailAddresses.mask(address);
    }

    private static String body(String code) {
        return """
                %s is your HouseHunting verification code.

                It confirms you can receive mail at this address, which puts a
                "Work email verified" badge on your listings. It expires in %d
                minutes.

                We never show this address to anybody. Only the badge is public.

                If you did not ask for this, ignore it — nothing has changed on
                any account.
                """.formatted(code, CODE_VALIDITY.toMinutes());
    }

    // ---- Checking ---------------------------------------------------------

    /**
     * Checks a submitted code against the live challenge.
     *
     * <p>{@code address} comes from the session, not from the request body.
     * Taking it from the form would let somebody request a code at an address
     * they control and then submit a different one at the final step — and the
     * address they submitted is the one that would be written to their profile.
     *
     * <p>Returns rather than throws, and the reason is worth stating plainly:
     * this method is transactional and it increments an attempt counter.
     * Throwing "wrong code" would roll the transaction back, discarding the
     * increment, and the five-guess cap would silently become no cap at all.
     */
    @Transactional
    public WorkEmailResult verify(UUID userId, String address, String submittedCode) {
        Instant now = clock.instant();

        Optional<WorkEmailChallenge> found =
                challenges.findFirstByUserIdAndConsumedAtIsNullOrderByCreatedAtDesc(userId);
        if (found.isEmpty()) {
            return new WorkEmailResult.NoChallenge();
        }

        WorkEmailChallenge challenge = found.get();

        // The challenge must belong to the address in the session. If they do
        // not match, the session has moved on and this challenge is stale.
        if (!challenge.getEmailHash().equals(Hashing.sha256Hex(address))) {
            return new WorkEmailResult.NoChallenge();
        }
        if (challenge.isExpired(now)) {
            return new WorkEmailResult.Expired();
        }
        if (!challenge.hasAttemptsLeft()) {
            return new WorkEmailResult.TooManyAttempts();
        }
        if (!OtpCodes.isWellFormed(submittedCode)) {
            // Not counted as an attempt. A mistyped length is a slip, not a
            // guess, and spending the budget on it means a fat-fingered person
            // gets locked out without ever having guessed anything.
            return new WorkEmailResult.Malformed();
        }

        challenge.recordAttempt();

        String submittedHmac = Hashing.hmacSha256Hex(submittedCode, config.secretBytes());
        boolean matches = Hashing.constantTimeEquals(submittedHmac, challenge.getCodeHmac());

        if (!matches) {
            if (!challenge.hasAttemptsLeft()) {
                challenge.exhaust(now);
                challenges.save(challenge);
                return new WorkEmailResult.TooManyAttempts();
            }
            challenges.save(challenge);
            return new WorkEmailResult.WrongCode(challenge.attemptsRemaining());
        }

        challenge.consume(now);
        challenges.save(challenge);

        return record(userId, address);
    }

    /**
     * Writes the proven address and sets the badge.
     *
     * <p><b>Why this queries first instead of catching the constraint.</b> The
     * first version of this method let user_private_work_email_uq do the work
     * and caught {@code DataIntegrityViolationException}. It does not work, and
     * the way it fails is worth knowing: when a statement violates a
     * constraint, the transaction is marked rollback-only before the exception
     * is thrown. Catching it changes nothing about that mark — the method
     * returns a tidy {@code AlreadyUsed}, and then the commit blows up with
     * {@code UnexpectedRollbackException} from somewhere with no connection to
     * the cause. <em>You cannot catch a constraint violation and carry on in
     * the same transaction.</em>
     *
     * <p>So: ask first. The honest cost is a race — two requests can both see
     * the address free and both try to claim it. The unique index is still
     * there and still the real guarantee, so the outcome of that race is one
     * success and one failed request, never two badges. A failed request in a
     * window of microseconds, for somebody who would have been refused anyway,
     * is a trade worth taking over a wrong answer.
     */
    private WorkEmailResult record(UUID userId, String address) {
        if (userPrivates.existsByWorkEmailAndUserIdNot(address, userId)) {
            return new WorkEmailResult.AlreadyUsed();
        }

        // Every account gets its user_private row at sign-in, when the verified
        // phone number is written — so by the time anybody reaches this flow
        // the row exists. Creating one here instead would mean inserting with
        // a null phone_e164, which the column forbids: the failure would be a
        // constraint violation reported as "verification failed", three layers
        // from the thing that was actually wrong. Better to say so.
        UserPrivate priv = userPrivates.findById(userId).orElseThrow(() ->
                new IllegalStateException("No user_private row for " + userId
                        + "; every account should have had one created at sign-in"));
        priv.setWorkEmail(address);
        userPrivates.saveAndFlush(priv);

        User user = users.findById(userId).orElseThrow();
        user.verifyWorkEmail();
        users.save(user);

        return new WorkEmailResult.Verified(address);
    }

    // ---- Undo -------------------------------------------------------------

    /**
     * Removes the address and the badge.
     *
     * <p>Somebody who changes jobs must be able to take this off, and the
     * address must go with it — leaving a verified address behind after the
     * badge is gone means holding data about a former employer for no reason.
     */
    @Transactional
    public void remove(UUID userId) {
        userPrivates.findById(userId).ifPresent(priv -> {
            priv.clearWorkEmail();
            userPrivates.save(priv);
        });
        users.findById(userId).ifPresent(user -> {
            user.unverifyWorkEmail();
            users.save(user);
        });
    }

    /** Whether the badge is currently earned. */
    @Transactional(readOnly = true)
    public boolean isVerified(UUID userId) {
        return users.findById(userId).map(User::isWorkEmailVerified).orElse(false);
    }

    /** The masked address, for showing on the profile. Never the full one. */
    @Transactional(readOnly = true)
    public Optional<String> maskedAddress(UUID userId) {
        return userPrivates.findById(userId)
                .map(UserPrivate::getWorkEmail)
                .filter(a -> a != null && !a.isBlank())
                .map(WorkEmailAddresses::mask);
    }

    /** Asked for a code again too soon. */
    public static class ResendTooSoonException extends RuntimeException {
        public ResendTooSoonException(String message) {
            super(message);
        }
    }
}
