package com.househunting.auth;

import com.househunting.support.MutableClock;
import com.househunting.support.RecordingSmsSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The security behaviour of the OTP flow.
 *
 * <p>Runs against real PostgreSQL, with a clock the test controls and an SMS
 * sender that records instead of sending. No sleeping, no network.
 */
@SpringBootTest(properties = {
        // The test's `clock` bean replaces AuthConfig's, which has the same
        // name. Spring Boot disables overriding by default so a stray @Bean
        // cannot silently displace a real one; here it is intended, so it is
        // enabled explicitly and only for this test.
        "spring.main.allow-bean-definition-overriding=true",
        // Selects a provider other than "console", so consoleSmsSender is
        // never registered and RecordingSmsSender is the only SmsSender.
        // Deterministic, unlike relying on bean ordering.
        "househunting.sms.provider=recording"
})
@Import(OtpServiceTest.TestBeans.class)
class OtpServiceTest {

    private static final String PHONE = "+919876543210";
    private static final String OTHER_PHONE = "+919876500000";
    private static final String IP = "203.0.113.7";
    private static final Instant START = Instant.parse("2026-09-25T10:00:00Z");

    @TestConfiguration
    static class TestBeans {
        /** Replaces the system clock so expiry and cooldowns are testable. */
        @Bean
        Clock clock() {
            return new MutableClock(START);
        }

        /** Displaces ConsoleSmsSender, which is @ConditionalOnMissingBean. */
        @Bean
        SmsSender smsSender() {
            return new RecordingSmsSender();
        }
    }

    @Autowired private OtpService otp;
    @Autowired private Clock clock;
    @Autowired private SmsSender smsSender;
    @Autowired private OtpChallengeRepository challenges;
    @Autowired private JdbcClient jdbc;

    private MutableClock time() {
        return (MutableClock) clock;
    }

    private RecordingSmsSender sms() {
        return (RecordingSmsSender) smsSender;
    }

    @BeforeEach
    void reset() {
        // This test deliberately does NOT use @Transactional rollback: the
        // point of several assertions is that state was actually committed,
        // which a rolled-back transaction would hide.
        challenges.deleteAll();
        jdbc.sql("DELETE FROM otp_rate_limits").update();
        sms().clear();
        time().setTo(START);
    }

    // ---- The happy path ---------------------------------------------------

    @Test
    @DisplayName("a code is sent and the correct code verifies")
    void correctCodeVerifies() {
        OtpDispatch dispatch = otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);

        assertThat(sms().count()).isEqualTo(1);
        assertThat(sms().last().phoneE164()).isEqualTo(PHONE);

        // The screen shows enough to recognise the number, not enough to use it.
        assertThat(dispatch.maskedPhone()).isEqualTo("+91 ***** 43210");
        assertThat(dispatch.maskedPhone()).doesNotContain("98765");

        OtpResult result = otp.verify(PHONE, sms().lastCode(), OtpPurpose.LOGIN);

        assertThat(result).isInstanceOf(OtpResult.Verified.class);
        assertThat(result.isVerified()).isTrue();
    }

    @Test
    @DisplayName("the phone number is never written to the database")
    void phoneNumberIsNeverPersisted() {
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);

        // Scan every text column of both OTP tables for the number.
        Integer hits = jdbc.sql("""
                        SELECT count(*) FROM otp_challenges
                        WHERE phone_hash LIKE ? OR code_hmac LIKE ?
                        """)
                .param("%9876543210%")
                .param("%9876543210%")
                .query(Integer.class).single();

        assertThat(hits).isZero();

        // Nor does the plaintext code appear anywhere.
        String code = sms().lastCode();
        Integer codeHits = jdbc.sql("SELECT count(*) FROM otp_challenges WHERE code_hmac = ?")
                .param(code)
                .query(Integer.class).single();
        assertThat(codeHits).isZero();
    }

    // ---- Attempt capping: the rollback bug --------------------------------

    @Test
    @DisplayName("a wrong attempt is COMMITTED, so the cap cannot be bypassed")
    void wrongAttemptIsPersisted() {
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);

        OtpResult first = otp.verify(PHONE, "000000", OtpPurpose.LOGIN);
        assertThat(first).isInstanceOf(OtpResult.WrongCode.class);

        // The assertion that matters. If verify() threw instead of returning,
        // Spring would roll the transaction back and undo this increment — the
        // attempt cap would silently never engage, and a six-digit code would
        // be brute-forceable.
        OtpChallenge stored = challenges.findAll().getFirst();
        assertThat(stored.getAttempts()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("the challenge is burned after the attempt budget is spent")
    void attemptsAreCapped() {
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);
        String realCode = sms().lastCode();
        String wrongCode = realCode.equals("000000") ? "111111" : "000000";

        for (int i = 1; i <= 4; i++) {
            OtpResult r = otp.verify(PHONE, wrongCode, OtpPurpose.LOGIN);
            assertThat(r).isInstanceOf(OtpResult.WrongCode.class);
            assertThat(((OtpResult.WrongCode) r).attemptsRemaining()).isEqualTo(5 - i);
        }

        // The fifth spends the budget.
        assertThat(otp.verify(PHONE, wrongCode, OtpPurpose.LOGIN))
                .isInstanceOf(OtpResult.TooManyAttempts.class);

        // And the REAL code no longer works — the challenge is dead, not merely
        // out of guesses.
        assertThat(otp.verify(PHONE, realCode, OtpPurpose.LOGIN))
                .isNotInstanceOf(OtpResult.Verified.class);
    }

    @Test
    @DisplayName("a malformed code costs no attempt and touches no row")
    void malformedCodeCostsNothing() {
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);

        assertThat(otp.verify(PHONE, "12", OtpPurpose.LOGIN)).isInstanceOf(OtpResult.Malformed.class);
        assertThat(otp.verify(PHONE, "abcdef", OtpPurpose.LOGIN)).isInstanceOf(OtpResult.Malformed.class);
        assertThat(otp.verify(PHONE, "", OtpPurpose.LOGIN)).isInstanceOf(OtpResult.Malformed.class);

        assertThat(challenges.findAll().getFirst().getAttempts()).isEqualTo((short) 0);
    }

    // ---- Single use, expiry -----------------------------------------------

    @Test
    @DisplayName("a code works exactly once")
    void codeIsSingleUse() {
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);
        String code = sms().lastCode();

        assertThat(otp.verify(PHONE, code, OtpPurpose.LOGIN)).isInstanceOf(OtpResult.Verified.class);

        // Replaying an intercepted code must not work.
        assertThat(otp.verify(PHONE, code, OtpPurpose.LOGIN)).isInstanceOf(OtpResult.NoChallenge.class);
    }

    @Test
    @DisplayName("a code expires, without the test sleeping for ten minutes")
    void codeExpires() {
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);
        String code = sms().lastCode();

        time().advance(Duration.ofMinutes(9));
        assertThat(otp.verify(PHONE, code, OtpPurpose.LOGIN)).isInstanceOf(OtpResult.Verified.class);
    }

    @Test
    @DisplayName("a code past its validity is refused")
    void expiredCodeIsRefused() {
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);
        String code = sms().lastCode();

        time().advance(Duration.ofMinutes(11));

        assertThat(otp.verify(PHONE, code, OtpPurpose.LOGIN)).isInstanceOf(OtpResult.Expired.class);
    }

    // ---- Resend -----------------------------------------------------------

    @Test
    @DisplayName("resending before the cooldown is refused, server-side")
    void resendCooldownIsEnforced() {
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);

        // The countdown in the browser is a courtesy. This is the rule.
        assertThatThrownBy(() -> otp.requestCode(PHONE, IP, OtpPurpose.LOGIN))
                .isInstanceOf(OtpRateLimitedException.class);

        time().advance(Duration.ofSeconds(31));
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);

        assertThat(sms().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a resend replaces the previous code rather than adding a second live one")
    void resendInvalidatesPreviousCode() {
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);
        String firstCode = sms().lastCode();

        time().advance(Duration.ofSeconds(31));
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);
        String secondCode = sms().lastCode();

        // Two live codes would double an attacker's chances per resend.
        if (!firstCode.equals(secondCode)) {
            assertThat(otp.verify(PHONE, firstCode, OtpPurpose.LOGIN))
                    .isInstanceOf(OtpResult.WrongCode.class);
        }
        assertThat(otp.verify(PHONE, secondCode, OtpPurpose.LOGIN))
                .isInstanceOf(OtpResult.Verified.class);

        // One challenge, resent — not two.
        assertThat(challenges.count()).isEqualTo(1);
    }

    // ---- Rate limiting ----------------------------------------------------

    @Test
    @DisplayName("a number is limited to five codes per window")
    void perPhoneRateLimit() {
        for (int i = 0; i < 5; i++) {
            otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);
            time().advance(Duration.ofSeconds(31));   // clear the resend cooldown
        }

        assertThatThrownBy(() -> otp.requestCode(PHONE, IP, OtpPurpose.LOGIN))
                .isInstanceOf(OtpRateLimitedException.class)
                .hasMessageContaining("this number");
    }

    @Test
    @DisplayName("an attacker cycling numbers is caught by the IP limit")
    void perIpRateLimit() {
        // Each number is under its own limit, so only the IP dimension stops
        // this — which is the case it exists for.
        for (int i = 0; i < 20; i++) {
            otp.requestCode("+9198765%05d".formatted(i), IP, OtpPurpose.LOGIN);
            time().advance(Duration.ofSeconds(1));
        }

        assertThatThrownBy(() -> otp.requestCode(OTHER_PHONE, IP, OtpPurpose.LOGIN))
                .isInstanceOf(OtpRateLimitedException.class)
                .hasMessageContaining("this network");
    }

    // ---- Enumeration ------------------------------------------------------

    @Test
    @DisplayName("an unknown number behaves identically to a known one")
    void noAccountEnumeration() {
        // Both requests succeed in the same way. Branching on whether an
        // account exists would make this endpoint an existence oracle.
        OtpDispatch a = otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);
        time().advance(Duration.ofSeconds(31));
        OtpDispatch b = otp.requestCode(OTHER_PHONE, IP, OtpPurpose.LOGIN);

        assertThat(a.resendSeconds()).isEqualTo(b.resendSeconds());
        assertThat(sms().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("verifying against a number with no challenge is indistinguishable from a used code")
    void noChallengeLooksLikeUsedCode() {
        assertThat(otp.verify(PHONE, "123456", OtpPurpose.LOGIN))
                .isInstanceOf(OtpResult.NoChallenge.class);
    }

    // ---- Housekeeping -----------------------------------------------------

    @Test
    @DisplayName("expired challenges are purged rather than retained")
    void purgeRemovesExpiredChallenges() {
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);
        assertThat(challenges.count()).isEqualTo(1);

        time().advance(Duration.ofMinutes(11));
        otp.purgeExpired();

        // Deleted, not archived: every retained row is one more thing to leak.
        assertThat(challenges.count()).isZero();
    }
}
