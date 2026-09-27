package com.househunting.auth;

import com.househunting.common.Hashing;
import com.househunting.mail.EmailSender;
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
 * <p>Takes a SUBJECT from the caller — an E.164 number or a normalised email
 * address — and never persists it: only the SHA-256 goes to the database. The
 * plaintext lives in the caller's HTTP session for the length of the flow, so
 * an unauthenticated person's contact details are never written down.
 *
 * <p><b>One service for both channels.</b> Everything that makes a six-digit
 * code safe lives here and in {@link OtpChallenge}: the HMAC under a server
 * secret, the attempt cap, the resend cooldown, single use, two-dimensional
 * rate limiting. A separate email implementation would mean a second copy of
 * every one of those rules — and the second copy is the one that drifts,
 * because it is the one nobody is looking at when the first is changed.
 *
 * <p>The only thing that varies by channel is delivery, and that is decided in
 * one place: {@link #deliver}.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private final OtpChallengeRepository challenges;
    private final OtpRateLimiter rateLimiter;
    private final SmsSender sms;
    private final EmailSender email;
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
                      EmailSender email,
                      OtpProperties config,
                      Clock clock) {
        this.challenges = challenges;
        this.rateLimiter = rateLimiter;
        this.sms = sms;
        this.email = email;
        this.config = config;
        this.clock = clock;
    }

    /**
     * Sends a code, creating a challenge or reusing the live one.
     *
     * <p>Identical whether or not the subject has an account. Branching would
     * turn this endpoint into an account-existence oracle: an attacker could
     * learn which addresses are registered simply by watching the responses.
     *
     * @param subject   an E.164 number or a normalised email, per the purpose
     * @param ipAddress for the second rate-limit dimension
     * @throws OtpRateLimitedException if a limit or the resend cooldown applies
     */
    @Transactional
    public OtpDispatch requestCode(String subject, String ipAddress, OtpPurpose purpose) {
        Instant now = clock.instant();
        OtpChannel channel = purpose.channel();
        String subjectHash = Hashing.sha256Hex(subject);

        enforceRateLimits(subject, channel, ipAddress, now);

        Optional<OtpChallenge> existing = challenges
                .findFirstBySubjectHashAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                        subjectHash, purpose)
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
            challenge = new OtpChallenge(subjectHash, codeHmac, purpose, now,
                    config.codeValidity(), config.maxAttempts());
        }

        challenges.save(challenge);

        deliver(channel, subject, code);

        // Logs the challenge id, never the subject or the code. A log line is
        // readable by far more people than an inbox or a handset is.
        log.debug("OTP issued: challenge={} channel={} send={}",
                challenge.getId(), channel, challenge.getSendCount());

        return new OtpDispatch(
                channel.mask(subject),
                channel,
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
    public OtpResult verify(String subject, String submittedCode, OtpPurpose purpose) {
        Instant now = clock.instant();

        // Reject the wrong shape before touching the database: no lookup, no
        // attempt consumed, nothing learned by sending junk.
        if (!OtpCodes.isWellFormed(submittedCode)) {
            return new OtpResult.Malformed();
        }

        Optional<OtpChallenge> found = challenges
                .findFirstBySubjectHashAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                        Hashing.sha256Hex(subject), purpose);

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

        return new OtpResult.Verified(subject);
    }

    // ---- Delivery ---------------------------------------------------------

    /**
     * The ONLY place the two channels differ.
     *
     * <p>A switch over {@link OtpChannel} rather than an {@code if}, so adding
     * a channel later fails to compile here instead of silently sending
     * nothing — a bug that looks exactly like "the code never arrived".
     */
    private void deliver(OtpChannel channel, String subject, String code) {
        long minutes = config.codeValidity().toMinutes();

        switch (channel) {
            case SMS -> sms.send(subject,
                    "%s is your HouseHunting code. It expires in %d minutes."
                            .formatted(code, minutes));

            // The code goes in the SUBJECT LINE as well as the body. Most
            // people read it straight from the notification without opening
            // anything, which is the closest email gets to the immediacy of a
            // text message — and immediacy is the whole reason SMS was
            // preferred in the first place.
            case EMAIL -> email.send(subject,
                    "%s is your HouseHunting sign-in code".formatted(code),
                    emailBody(code, minutes));
        }
    }

    private static String emailBody(String code, long minutes) {
        return ("%s is your sign-in code for HouseHunting.\n"
                + "\n"
                + "It expires in %d minutes and can be used once.\n"
                + "\n"
                + "If you did not ask to sign in, you can ignore this email. "
                + "Nobody can reach an account without this code.\n")
                .formatted(code, minutes);
    }

    // ---- Rate limiting ----------------------------------------------------

    /**
     * Two dimensions, because each alone has a hole.
     *
     * <p>Per-subject stops one person being spammed, but an attacker cycling
     * through a thousand addresses walks past it while running up a real bill.
     * Per-IP catches that, but a proxy pool defeats it and an office NAT would
     * punish everyone behind it. Together they are awkward to evade without
     * being expensive to a legitimate user.
     *
     * <p>The per-subject budget is keyed on {@link OtpChannel#rateLimitScope()},
     * so email sends and SMS sends are counted in separate buckets. Sharing one
     * would force the cheap channel to live inside the expensive channel's
     * limit.
     */
    private void enforceRateLimits(String subject, OtpChannel channel,
                                   String ipAddress, Instant now) {
        Duration window = config.rateWindow();

        int perSubject = rateLimiter.recordAndCount(
                channel.rateLimitScope(), subject, now, window);
        if (perSubject > perSubjectLimit(channel)) {
            throw new OtpRateLimitedException(
                    channel == OtpChannel.EMAIL
                            ? "Too many codes requested for this address. Try again later."
                            : "Too many codes requested for this number. Try again later.",
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

    private int perSubjectLimit(OtpChannel channel) {
        return channel == OtpChannel.EMAIL ? config.perEmailPerHour() : config.perPhonePerHour();
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
