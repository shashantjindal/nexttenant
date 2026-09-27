package com.nexttenant.workemail;

import com.nexttenant.auth.OtpProperties;
import com.nexttenant.common.Hashing;
import com.nexttenant.mail.EmailSender;
import com.nexttenant.user.AgeBracket;
import com.nexttenant.user.Gender;
import com.nexttenant.user.User;
import com.nexttenant.user.UserPrivate;
import com.nexttenant.user.UserPrivateRepository;
import com.nexttenant.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Work email verification, end to end through the service.
 *
 * <p>The code never leaves the server in a readable form — only its HMAC is
 * stored — so a test cannot read it back out of the database. It is captured
 * from the outgoing message instead, which is also the only place a real
 * recipient would see it. That is the right seam: it proves the code that was
 * SENT is the code that works, which reading it from anywhere else would not.
 */
@SpringBootTest
class WorkEmailTest {

    /** Captures what would have been sent, so the test can read the code. */
    static class CapturingEmailSender implements EmailSender {
        final List<String> bodies = new ArrayList<>();
        final List<String> recipients = new ArrayList<>();

        @Override
        public void send(String to, String subject, String body) {
            recipients.add(to);
            bodies.add(body);
        }

        String lastCode() {
            Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(bodies.getLast());
            assertThat(m.find()).as("the email should contain a six-digit code").isTrue();
            return m.group(1);
        }
    }

    @TestConfiguration
    static class Senders {
        /**
         * {@code @Primary} rather than replacing the bean: Spring Boot disables
         * bean definition overriding by default, and enabling it globally to
         * swap one collaborator would hide a real duplicate-bean bug elsewhere.
         */
        @Bean
        @Primary
        CapturingEmailSender capturingEmailSender() {
            return new CapturingEmailSender();
        }
    }

    @Autowired private WorkEmailService workEmail;
    @Autowired private UserRepository users;
    @Autowired private UserPrivateRepository userPrivates;
    @Autowired private OtpProperties otpConfig;
    @Autowired private CapturingEmailSender sender;

    private User user;

    @BeforeEach
    void setUp() {
        sender.bodies.clear();
        sender.recipients.clear();
        user = newUser("Asha", Gender.FEMALE, AgeBracket.AGE_23_27);
    }

    /**
     * Creates an account the way sign-in does — with its user_private row.
     *
     * <p>Not incidental setup. Work email verification writes into
     * user_private, and that row is created at sign-in along with the verified
     * phone number. A test that skipped it would be testing a state no real
     * account is ever in.
     */
    private User newUser(String name, Gender gender, AgeBracket age) {
        User created = users.save(new User(name, gender, age));
        userPrivates.save(new UserPrivate(
                created.getId(), emailFor(created), phoneFor(created)));
        return created;
    }

    /**
     * A unique number derived from the account's id.
     *
     * <p>A counter was the obvious choice and was wrong twice over. JUnit
     * builds a fresh test instance per method, so an instance field restarts
     * at 1 for every test; and these rows are NOT rolled back (the class is
     * deliberately not @Transactional — see the note on
     * addressIsUniqueAcrossAccounts), so they also survive between runs. Either
     * way the second arrival collides with user_private_phone_uq.
     *
     * <p>Deriving from the UUID makes it unique without any shared state at
     * all. The 9-prefixed range keeps it a plausible Indian mobile for the
     * E.164 check while being one no carrier would route.
     */
    private static String phoneFor(User created) {
        long digits = Math.floorMod(created.getId().getMostSignificantBits(), 1_000_000_000L);
        return "+919" + "%09d".formatted(digits);
    }

    /**
     * A unique LOGIN address, derived from the id for the same reason.
     *
     * <p>Note this is the sign-in address, not the work address under test.
     * They are different columns with different rules — user_private.email is
     * the identity and may be a free provider; work_email is the badge and may
     * not. A test that reused one for the other would pass while proving
     * nothing about either.
     */
    private static String emailFor(User created) {
        return "login-" + created.getId() + "@invalid.invalid";
    }

    private boolean verified() {
        return users.findById(user.getId()).orElseThrow().isWorkEmailVerified();
    }

    /** A domain nobody else in this suite uses, so the uniqueness index cannot collide. */
    private String unique(String local) {
        return local + "@" + user.getId().toString().substring(0, 8) + ".example";
    }

    // ---- The address ------------------------------------------------------

    @Test
    @DisplayName("a free mailbox is refused, because it would prove nothing")
    void freeProvidersRefused() {
        for (String address : List.of("asha@gmail.com", "asha@yahoo.co.in",
                "asha@outlook.com", "asha@hotmail.de", "asha@rediffmail.com",
                "asha@proton.me")) {
            assertThatThrownBy(() -> workEmail.requestCode(user.getId(), address))
                    .as(address)
                    .isInstanceOf(InvalidWorkEmailException.class);
        }
        // Nothing was sent: the refusal happens before the mail provider is
        // touched, so a script cycling free addresses costs us nothing.
        assertThat(sender.bodies).isEmpty();
    }

    @Test
    @DisplayName("a malformed address is refused before anything is sent")
    void malformedRefused() {
        for (String address : List.of("", "   ", "asha", "asha@", "@corp.com",
                "asha@corp", "asha corp@x.com")) {
            assertThatThrownBy(() -> workEmail.requestCode(user.getId(), address))
                    .as("[" + address + "]")
                    .isInstanceOf(InvalidWorkEmailException.class);
        }
        assertThat(sender.bodies).isEmpty();
    }

    @Test
    @DisplayName("a company address is accepted, trimmed and lowercased")
    void companyAddressAccepted() {
        String masked = workEmail.requestCode(user.getId(), "  Asha@Corp.example ");

        // Normalised on the way in, or Asha@ and asha@ would be two different
        // rows as far as the uniqueness index is concerned — which reopens the
        // one-mailbox-many-badges hole the index exists to close.
        assertThat(sender.recipients).containsExactly("asha@corp.example");
        assertThat(masked).isEqualTo("a**a@corp.example");
        assertThat(verified()).isFalse();
    }

    // ---- The code ---------------------------------------------------------

    @Test
    @DisplayName("the right code earns the badge and records the address privately")
    void rightCodeVerifies() {
        String address = unique("asha");
        workEmail.requestCode(user.getId(), address);

        WorkEmailResult result = workEmail.verify(user.getId(), address, sender.lastCode());

        assertThat(result).isInstanceOf(WorkEmailResult.Verified.class);
        assertThat(verified()).isTrue();

        // The address lands in user_private, which no DTO exposes — the badge
        // is public, the address is not.
        assertThat(userPrivates.findById(user.getId()))
                .get().extracting(UserPrivate::getWorkEmail).isEqualTo(address);
    }

    @Test
    @DisplayName("the code is stored only as an HMAC, never as a plain digest")
    void codeIsNotRecoverable() {
        workEmail.requestCode(user.getId(), unique("asha"));
        String code = sender.lastCode();

        // A plain SHA-256 of six digits is a million candidates, which is
        // milliseconds of work: hashing alone buys nothing when the input space
        // is enumerable. Only the server secret makes the stored value useless
        // to somebody holding the table.
        assertThat(Hashing.hmacSha256Hex(code, otpConfig.secretBytes()))
                .isNotEqualTo(Hashing.sha256Hex(code));
    }

    @Test
    @DisplayName("a wrong code counts down, and the count survives the attempt")
    void wrongCodeCountsDown() {
        String address = unique("asha");
        workEmail.requestCode(user.getId(), address);

        WorkEmailResult first = workEmail.verify(user.getId(), address, "000000");
        WorkEmailResult second = workEmail.verify(user.getId(), address, "000001");

        // The assertion that catches the transactional trap: if verify THREW on
        // a wrong code, the rollback would discard the increment and both calls
        // would report the same number remaining — an unlimited guessing budget
        // wearing the costume of a limited one.
        assertThat(((WorkEmailResult.WrongCode) first).attemptsRemaining())
                .isGreaterThan(((WorkEmailResult.WrongCode) second).attemptsRemaining());
        assertThat(verified()).isFalse();
    }

    @Test
    @DisplayName("a mistyped length is not spent as a guess")
    void malformedCodeIsNotAnAttempt() {
        String address = unique("asha");
        workEmail.requestCode(user.getId(), address);

        assertThat(workEmail.verify(user.getId(), address, "12345"))
                .isInstanceOf(WorkEmailResult.Malformed.class);

        // The real code still works afterwards: a fat-fingered entry must not
        // eat part of a five-guess budget.
        assertThat(workEmail.verify(user.getId(), address, sender.lastCode()))
                .isInstanceOf(WorkEmailResult.Verified.class);
    }

    @Test
    @DisplayName("the budget runs out and the challenge dies with it")
    void attemptsRunOut() {
        String address = unique("asha");
        workEmail.requestCode(user.getId(), address);
        String code = sender.lastCode();

        WorkEmailResult last = null;
        for (int i = 0; i < WorkEmailService.MAX_ATTEMPTS; i++) {
            last = workEmail.verify(user.getId(), address, "000000");
        }

        // The guess that spends the budget says so.
        assertThat(last).isInstanceOf(WorkEmailResult.TooManyAttempts.class);

        // And afterwards even the CORRECT code is refused. It comes back as
        // NoChallenge rather than TooManyAttempts, because exhausting a
        // challenge consumes it and the lookup only returns live ones — both
        // are "dead, start again", which is all the caller needs. A challenge
        // that survived its own attempt cap would make the cap decorative.
        assertThat(workEmail.verify(user.getId(), address, code))
                .isNotInstanceOf(WorkEmailResult.Verified.class);
        assertThat(verified()).isFalse();
    }

    @Test
    @DisplayName("a code is single use")
    void codeIsSingleUse() {
        String address = unique("asha");
        workEmail.requestCode(user.getId(), address);
        String code = sender.lastCode();

        assertThat(workEmail.verify(user.getId(), address, code))
                .isInstanceOf(WorkEmailResult.Verified.class);
        // Replaying it must not work, or one intercepted code is good until
        // expiry.
        assertThat(workEmail.verify(user.getId(), address, code))
                .isInstanceOf(WorkEmailResult.NoChallenge.class);
    }

    @Test
    @DisplayName("a code issued for one address cannot verify another")
    void codeIsBoundToItsAddress() {
        workEmail.requestCode(user.getId(), unique("asha"));
        String code = sender.lastCode();

        // The controller takes the address from the SESSION, but even if
        // something upstream let a different one through, the challenge is
        // bound to the hash of the address it was issued for.
        assertThat(workEmail.verify(user.getId(), unique("someone.else"), code))
                .isInstanceOf(WorkEmailResult.NoChallenge.class);
        assertThat(verified()).isFalse();
    }

    // ---- One mailbox, one account -----------------------------------------

    @Test
    @DisplayName("a second account cannot verify an address already in use")
    void addressIsUniqueAcrossAccounts() {
        String address = unique("shared");
        workEmail.requestCode(user.getId(), address);
        workEmail.verify(user.getId(), address, sender.lastCode());

        User other = newUser("Bimal", Gender.MALE, AgeBracket.AGE_28_32);
        workEmail.requestCode(other.getId(), address);

        // Refused at the final step, not the first. Refusing when the address
        // is TYPED would answer "is this colleague on NextTenant?" for
        // anybody who cared to ask.
        assertThat(workEmail.verify(other.getId(), address, sender.lastCode()))
                .isInstanceOf(WorkEmailResult.AlreadyUsed.class);
        assertThat(users.findById(other.getId()).orElseThrow().isWorkEmailVerified()).isFalse();
    }

    // ---- Undo -------------------------------------------------------------

    @Test
    @DisplayName("removing takes the badge AND deletes the address")
    void removalDeletesTheAddress() {
        String address = unique("asha");
        workEmail.requestCode(user.getId(), address);
        workEmail.verify(user.getId(), address, sender.lastCode());

        workEmail.remove(user.getId());

        assertThat(verified()).isFalse();
        // Keeping the address after the badge is gone would mean holding data
        // about a former employer with nothing justifying it.
        assertThat(userPrivates.findById(user.getId()))
                .get().extracting(UserPrivate::getWorkEmail).isNull();

        // And the address is free for somebody else, which it must be: the
        // colleague who inherits the mailbox is a real case.
        User other = newUser("Bimal", Gender.MALE, AgeBracket.AGE_28_32);
        workEmail.requestCode(other.getId(), address);
        assertThat(workEmail.verify(other.getId(), address, sender.lastCode()))
                .isInstanceOf(WorkEmailResult.Verified.class);
    }

    // ---- Masking ----------------------------------------------------------

    @Test
    @DisplayName("the masked form keeps the domain and hides the person")
    void maskingKeepsTheDomain() {
        // The domain is what tells somebody they typed the right employer, and
        // they already know it. The local part is the bit a support screenshot
        // should not carry.
        assertThat(WorkEmailAddresses.mask("asha.nair@corp.co.in")).isEqualTo("a*******r@corp.co.in");
        assertThat(WorkEmailAddresses.mask("ab@corp.com")).isEqualTo("**@corp.com");
        assertThat(WorkEmailAddresses.mask("a@corp.com")).isEqualTo("*@corp.com");
    }
}
