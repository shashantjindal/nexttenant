package com.househunting.auth;

import com.househunting.mail.EmailSender;
import com.househunting.support.MutableClock;
import com.househunting.support.RecordingEmailSender;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Codes delivered by email, and the routing that decides which channel carries
 * them.
 *
 * <p>Both recording senders are registered so the test can prove a code went
 * down ONE pipe and not the other — the failure this guards against is a
 * channel switch that silently sends nothing, which looks exactly like "the
 * code never arrived".
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "househunting.sms.provider=recording",
        "househunting.mail.provider=recording"
})
@Import(OtpEmailChannelTest.TestBeans.class)
class OtpEmailChannelTest {

    private static final String EMAIL = "someone@example.com";
    private static final String PHONE = "+919876543210";
    private static final String IP = "203.0.113.9";
    private static final Instant START = Instant.parse("2026-09-27T10:00:00Z");

    @TestConfiguration
    static class TestBeans {
        @Bean
        Clock clock() {
            return new MutableClock(START);
        }

        @Bean
        SmsSender smsSender() {
            return new RecordingSmsSender();
        }

        @Bean
        EmailSender emailSender() {
            return new RecordingEmailSender();
        }
    }

    @Autowired private OtpService otp;
    @Autowired private Clock clock;
    @Autowired private SmsSender smsSender;
    @Autowired private EmailSender emailSender;
    @Autowired private OtpChallengeRepository challenges;
    @Autowired private JdbcClient jdbc;

    private MutableClock time() {
        return (MutableClock) clock;
    }

    private RecordingSmsSender sms() {
        return (RecordingSmsSender) smsSender;
    }

    private RecordingEmailSender mail() {
        return (RecordingEmailSender) emailSender;
    }

    @BeforeEach
    void reset() {
        challenges.deleteAll();
        jdbc.sql("DELETE FROM otp_rate_limits").update();
        sms().clear();
        mail().clear();
        time().setTo(START);
    }

    // ---- Routing ----------------------------------------------------------

    @Test
    @DisplayName("an email login code goes by email and not by SMS")
    void emailLoginUsesTheEmailSender() {
        OtpDispatch dispatch = otp.requestCode(EMAIL, IP, OtpPurpose.EMAIL_LOGIN);

        assertThat(mail().count()).isEqualTo(1);
        assertThat(mail().last().to()).isEqualTo(EMAIL);
        // The half that matters: nothing went down the other pipe. A channel
        // switch that sent on BOTH would pass a test that only checked email.
        assertThat(sms().count()).isZero();

        assertThat(dispatch.channel()).isEqualTo(OtpChannel.EMAIL);
    }

    @Test
    @DisplayName("a phone login code still goes by SMS")
    void phoneLoginStillUsesSms() {
        // The SMS path is unreachable from the UI today, but it is the path
        // that returns when DLT clears. A test is what stops it rotting.
        otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);

        assertThat(sms().count()).isEqualTo(1);
        assertThat(mail().count()).isZero();
    }

    @Test
    @DisplayName("the code appears in the subject line as well as the body")
    void codeIsInTheSubjectLine() {
        otp.requestCode(EMAIL, IP, OtpPurpose.EMAIL_LOGIN);
        String code = mail().lastCode();

        // Most people read a code off the notification without opening
        // anything. Putting it only in the body costs them a tap and several
        // seconds, which is the whole margin email has to make up against SMS.
        assertThat(mail().last().subject()).contains(code);
        assertThat(mail().last().body()).contains(code);
    }

    // ---- The same discipline as the SMS path ------------------------------

    @Test
    @DisplayName("a code sent by email verifies, and only once")
    void emailCodeVerifiesAndIsSingleUse() {
        otp.requestCode(EMAIL, IP, OtpPurpose.EMAIL_LOGIN);
        String code = mail().lastCode();

        assertThat(otp.verify(EMAIL, code, OtpPurpose.EMAIL_LOGIN))
                .isInstanceOf(OtpResult.Verified.class);

        // Replay. Without consume(), one intercepted code works until expiry.
        assertThat(otp.verify(EMAIL, code, OtpPurpose.EMAIL_LOGIN))
                .isInstanceOf(OtpResult.NoChallenge.class);
    }

    @Test
    @DisplayName("a code issued for one purpose does not work for another")
    void purposesDoNotCrossOver() {
        otp.requestCode(EMAIL, IP, OtpPurpose.EMAIL_LOGIN);
        String code = mail().lastCode();

        // The lookup is keyed on (subject_hash, purpose). Were it keyed on the
        // subject alone, a code issued for one flow would unlock another.
        assertThat(otp.verify(EMAIL, code, OtpPurpose.LOGIN))
                .isInstanceOf(OtpResult.NoChallenge.class);
    }

    @Test
    @DisplayName("the address is never written to the database")
    void addressIsNeverPersisted() {
        otp.requestCode(EMAIL, IP, OtpPurpose.EMAIL_LOGIN);

        Integer hits = jdbc.sql("""
                        SELECT count(*) FROM otp_challenges
                        WHERE subject_hash LIKE ? OR code_hmac LIKE ?
                        """)
                .param("%someone%")
                .param("%someone%")
                .query(Integer.class).single();

        assertThat(hits).isZero();

        // Nor in the rate-limit table, whose keys are hashed for this reason.
        Integer limitHits = jdbc.sql(
                        "SELECT count(*) FROM otp_rate_limits WHERE key_hash LIKE ?")
                .param("%someone%")
                .query(Integer.class).single();

        assertThat(limitHits).isZero();
    }

    // ---- Separate budgets --------------------------------------------------

    @Test
    @DisplayName("exhausting the SMS budget leaves the email budget untouched")
    void channelsHaveSeparateRateLimits() {
        // Five sends to one number: the configured per-phone hourly budget.
        // The clock moves past the resend cooldown each time, because the
        // cooldown and the rate limit are different rules and this test is
        // about the second.
        for (int i = 0; i < 5; i++) {
            otp.requestCode(PHONE, IP, OtpPurpose.LOGIN);
            time().advance(java.time.Duration.ofMinutes(1));
        }

        assertThatThrownBy(() -> otp.requestCode(PHONE, IP, OtpPurpose.LOGIN))
                .isInstanceOf(OtpRateLimitedException.class);

        // The point of the test. Sharing one bucket would force the cheap
        // channel to live inside the expensive channel's limit, and somebody
        // would be locked out of signing in because of texts they never got.
        OtpDispatch dispatch = otp.requestCode(EMAIL, IP, OtpPurpose.EMAIL_LOGIN);

        assertThat(dispatch.channel()).isEqualTo(OtpChannel.EMAIL);
        assertThat(mail().count()).isEqualTo(1);
    }
}
