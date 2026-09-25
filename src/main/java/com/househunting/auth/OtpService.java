package com.househunting.auth;

import com.househunting.common.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Issues and checks one-time passcodes.
 *
 * <p>Takes an E.164 number from the caller and never persists it: only the
 * SHA-256 goes to the database. The plaintext lives in the caller's HTTP
 * session for the length of the flow, so an unauthenticated person's number is
 * never written down.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private final OtpChallengeRepository challenges;
    private final OtpRateLimiter rateLimiter;
    private final SmsSender sms;
    private final OtpProperties config;

    /**
     * Clock is injected rather than calling Instant.now() directly.
     *
     * <p>Every rule here is time-dependent — expiry, cooldown, rate windows —
     * and a test that has to sleep for thirty seconds to check a cooldown is a
     * test nobody runs. With an injected clock, time is an input.
     */
    private final Clock clock;

    public OtpService(OtpChallengeRepository challenges,
                      OtpRateLimiter rateLimiter,
                      SmsSender sms,
                      OtpProperties config,
                      Clock clock) {
        this.challenges = challenges;
        this.rateLimiter = rateLimiter;
        this.sms = sms;
        this.config = config;
        this.clock = clock;
    }

    /**
     * Sends a code, creating a challenge or reusing the live one.
     *
     * <p>Identical whether or not the number has an account. Branching would
     * turn this endpoint into an account-existence oracle: an attacker could
     * learn which numbers are registered simply by watching the responses.
     *
     * @param ipAddress for the second rate-limit dimension
     * @throws OtpRateLimitedException if a limit or the resend cooldown applies
     */
    @Transactional
    public OtpDispatch requestCode(String phoneE164, String ipAddress, OtpPurpose purpose) {
        Instant now = clock.instant();
        String phoneHash = Hashing.sha256Hex(phoneE164);

        enforceRateLimits(phoneE164, ipAddress, now);

        Optional<OtpChallenge> existing = challenges
                .findFirstByPhoneHashAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                        phoneHash, purpose)
                .filter(c -> !c.isExpired(now));

        String code = OtpCodes.generate();
        String codeHmac = Hashing.hmacSha256Hex(code, config.secretBytes());

        OtpChallenge challenge;
        if (existing.isPresent()) {
            challenge = existing.get();

            // The countdown in the browser is a courtesy; this is the rule.
            // Anything enforced only in the client is not enforced.
            if (!challenge.canResend(now, config.resendCooldown(), config.maxSends())) {
                throw new OtpRateLimitedException(
                        "Please wait before asking for another code",
                        challenge.resendAvailableIn(now, config.resendCooldown()));
            }

            // Replaces the code rather than adding a second live one: several
            // valid codes at once multiply an attacker's chances by the number
            // of resends they can trigger.
            challenge.resend(codeHmac, now, config.codeValidity());
        } else {
            challenge = new OtpChallenge(phoneHash, codeHmac, purpose, now,
                    config.codeValidity(), config.maxAttempts());
        }

        challenges.save(challenge);

        sms.send(phoneE164, "%s is your HouseHunting code. It expires in %d minutes."
                .formatted(code, config.codeValidity().toMinutes()));

        // Logs the challenge id, never the number or the code. A log line is
        // readable by far more people than a handset is.
        log.debug("OTP issued: challenge={} send={}", challenge.getId(), challenge.getSendCount());

        return new OtpDispatch(
                PhoneNumbers.mask(phoneE164),
                config.resendCooldown(),
                challenge.getExpiresAt());
    }

    /**
     * Checks a submitted code.
     *
     * <p>Returns an {@link OtpResult} rather than throwing, so the attempt
     * counter is never rolled back by the exception reporting the failure. See
     * {@link OtpResult} for why that matters.
     */
    @Transactional
    public OtpResult verify(String phoneE164, String submittedCode, OtpPurpose purpose) {
        Instant now = clock.instant();

        // Reject the wrong shape before touching the database: no lookup, no
        // attempt consumed, nothing learned by sending junk.
        if (!OtpCodes.isWellFormed(submittedCode)) {
            return new OtpResult.Malformed();
        }

        Optional<OtpChallenge> found = challenges
                .findFirstByPhoneHashAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                        Hashing.sha256Hex(phoneE164), purpose);

        if (found.isEmpty()) {
            return new OtpResult.NoChallenge();
        }

        OtpChallenge challenge = found.get();

        if (challenge.isExpired(now)) {
            return new OtpResult.Expired();
        }
        if (!challenge.hasAttemptsLeft()) {
            challenge.exhaust(now);
            return new OtpResult.TooManyAttempts();
        }

        // Counted BEFORE the comparison, and for right answers as well as
        // wrong. Counting only failures would let an attacker interleave a
        // known-good code on their own challenge to keep a budget low.
        challenge.recordAttempt();

        String submittedHmac = Hashing.hmacSha256Hex(submittedCode, config.secretBytes());
        boolean matches = Hashing.constantTimeEquals(submittedHmac, challenge.getCodeHmac());

        if (!matches) {
            if (!challenge.hasAttemptsLeft()) {
                // Burn it now rather than leave a dead challenge lying around
                // as a target until it expires.
                challenge.exhaust(now);
                challenges.save(challenge);
                return new OtpResult.TooManyAttempts();
            }
            challenges.save(challenge);
            return new OtpResult.WrongCode(challenge.attemptsRemaining());
        }

        // Single use: without this, one intercepted code replays until expiry.
        challenge.consume(now);
        challenges.save(challenge);

        return new OtpResult.Verified(phoneE164);
    }

    /**
     * Two dimensions, because each alone has a hole.
     *
     * <p>Per-number stops one person being spammed, but an attacker cycling
     * through a thousand numbers walks past it while running up a real SMS
     * bill. Per-IP catches that, but a proxy pool defeats it and an office NAT
     * would punish everyone behind it. Together they are awkward to evade
     * without being expensive to a legitimate user.
     */
    private void enforceRateLimits(String phoneE164, String ipAddress, Instant now) {
        Duration window = config.rateWindow();

        int perPhone = rateLimiter.recordAndCount(RateLimitScope.PHONE, phoneE164, now, window);
        if (perPhone > config.perPhonePerHour()) {
            throw new OtpRateLimitedException(
                    "Too many codes requested for this number. Try again later.",
                    rateLimiter.timeUntilReset(now, window));
        }

        if (ipAddress != null && !ipAddress.isBlank()) {
            int perIp = rateLimiter.recordAndCount(RateLimitScope.IP, ipAddress, now, window);
            if (perIp > config.perIpPerHour()) {
                throw new OtpRateLimitedException(
                        "Too many codes requested from this network. Try again later.",
                        rateLimiter.timeUntilReset(now, window));
            }
        }
    }

    /** Removes expired challenges and stale rate-limit windows. */
    @Transactional
    public void purgeExpired() {
        Instant now = clock.instant();
        int removedChallenges = challenges.deleteExpiredBefore(now);
        int removedWindows = rateLimiter.purgeOlderThan(now.minus(config.rateWindow().multipliedBy(2)));
        if (removedChallenges > 0 || removedWindows > 0) {
            log.debug("OTP purge: {} challenges, {} rate windows", removedChallenges, removedWindows);
        }
    }
}
