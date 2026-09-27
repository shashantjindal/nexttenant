package com.nexttenant.auth;

import com.nexttenant.common.Hashing;
import com.nexttenant.support.RecordingEmailSender;
import com.nexttenant.mail.EmailSender;
import com.nexttenant.user.UserPrivate;
import com.nexttenant.user.UserPrivateRepository;
import com.nexttenant.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sign-in flow end to end, through the HTTP layer.
 *
 * <p>Since V14 the flow is: email, code, then a profile step that collects the
 * first name AND the mobile number. The address is the identity; the number is
 * contact detail, typed rather than proved.
 */
@SpringBootTest(properties = {
        // Selects a provider other than "console", so ConsoleEmailSender never
        // registers and RecordingEmailSender is the only EmailSender.
        // Deterministic, unlike relying on bean ordering.
        "nexttenant.mail.provider=recording",
        // Spring Session's filter supplies sessions from its repository and
        // ignores MockMvc's MockHttpSession, so the subject would not carry
        // between steps. Switched off here because these tests exercise OUR
        // flow; that the JDBC store works is Spring Session's own concern and
        // was verified against the running app.
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
@AutoConfigureMockMvc
@Import(AuthFlowTest.TestBeans.class)
class AuthFlowTest {

    private static final String EMAIL = "priya@example.com";
    private static final String RAW_PHONE = "98765 43210";
    private static final String E164 = "+919876543210";

    @TestConfiguration
    static class TestBeans {
        @Bean
        EmailSender emailSender() {
            return new RecordingEmailSender();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private EmailSender emailSender;
    @Autowired private UserRepository users;
    @Autowired private UserPrivateRepository userPrivates;
    @Autowired private OtpChallengeRepository challenges;
    @Autowired private JdbcClient jdbc;

    private RecordingEmailSender mail() {
        return (RecordingEmailSender) emailSender;
    }

    @BeforeEach
    void reset() {
        challenges.deleteAll();
        jdbc.sql("DELETE FROM otp_rate_limits").update();
        // Remove any account left by an earlier run of this test. Keyed on the
        // EMAIL now, because that is what identifies an account.
        userPrivates.findByEmailHash(Hashing.sha256Hex(EMAIL))
                .ifPresent(p -> users.deleteById(p.getUserId()));
        userPrivates.findByPhoneHash(Hashing.sha256Hex(E164))
                .ifPresent(p -> users.deleteById(p.getUserId()));
        mail().clear();
    }

    // ---- CSRF -------------------------------------------------------------

    @Test
    @DisplayName("a POST without a CSRF token is rejected")
    void csrfIsRequired() throws Exception {
        // Without this, any page on the internet could submit a form to our
        // server and the browser would attach the user's session cookie,
        // because cookies are sent by destination rather than by origin.
        mockMvc.perform(post("/login/email").param("email", EMAIL))
                .andExpect(status().isForbidden());

        assertThat(mail().count()).isZero();
    }

    // ---- Access control ---------------------------------------------------

    @Test
    @DisplayName("browsing works signed out, but the dashboard does not")
    void publicAndPrivateRoutes() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/login")).andExpect(status().isOk());

        // anyRequest().authenticated() means a route is private unless it was
        // deliberately listed as public — so forgetting one fails safe.
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ---- The full flow ----------------------------------------------------

    @Test
    @DisplayName("email, code and profile produce a signed-in account with a number")
    void fullSignUpFlow() throws Exception {
        MockHttpSession session = new MockHttpSession();

        // 1. Address -> code sent, redirect to the code screen.
        mockMvc.perform(post("/login/email").param("email", EMAIL).with(csrf()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login/code"));

        assertThat(mail().count()).isEqualTo(1);
        assertThat(mail().last().to()).isEqualTo(EMAIL);

        // 2. Code -> new account, so the profile step.
        mockMvc.perform(post("/login/code").param("code", mail().lastCode()).with(csrf()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login/profile"));

        // 3. Profile -> signed in. The number is collected HERE.
        mockMvc.perform(post("/login/profile")
                        .param("firstName", "Priya")
                        .param("phone", RAW_PHONE)
                        .param("gender", "FEMALE")
                        .param("ageBracket", "AGE_23_27")
                        .with(csrf()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));

        // 4. The dashboard now renders for this session.
        MvcResult dash = mockMvc.perform(get("/dashboard").session(session))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(dash.getResponse().getContentAsString()).contains("Priya");

        // 5. Both contact details landed, normalised, on one row.
        UserPrivate stored = userPrivates.findByEmailHash(Hashing.sha256Hex(EMAIL)).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo(EMAIL);
        // "98765 43210" in, E.164 out — otherwise one number becomes several
        // accounts depending on how it was typed.
        assertThat(stored.getPhoneE164()).isEqualTo(E164);
    }

    @Test
    @DisplayName("a typed number is NOT a verified number")
    void collectingTheNumberDoesNotVerifyIt() throws Exception {
        signUp(new MockHttpSession());

        UserPrivate priv = userPrivates.findByEmailHash(Hashing.sha256Hex(EMAIL)).orElseThrow();

        assertThat(priv.getPhoneE164()).isEqualTo(E164);
        // The badge is the whole point of this assertion. Nothing has proved
        // ownership of that number — no SMS was sent and none could be — so
        // users.phone_verified_at must stay null. A flow that set it would make
        // the badge mean "typed", and a badge that lies is worse than none.
        assertThat(users.findById(priv.getUserId()).orElseThrow().isPhoneVerified()).isFalse();
    }

    @Test
    @DisplayName("an existing account skips the profile step")
    void returningUserSkipsProfile() throws Exception {
        signUp(new MockHttpSession());

        // Sign in again on a fresh session.
        MockHttpSession second = new MockHttpSession();
        mockMvc.perform(post("/login/email").param("email", EMAIL).with(csrf()).session(second));

        mockMvc.perform(post("/login/code").param("code", mail().lastCode()).with(csrf()).session(second))
                .andExpect(redirectedUrl("/dashboard"));
    }

    @Test
    @DisplayName("the address is matched case-insensitively and whitespace-insensitively")
    void addressIsCaseInsensitive() throws Exception {
        signUp(new MockHttpSession());
        var original = userPrivates.findByEmailHash(Hashing.sha256Hex(EMAIL)).orElseThrow();
        long accountsBefore = users.count();

        // A capitalised first letter from a phone keyboard, or a space picked
        // up pasting from a password manager. Without normalisation this is a
        // SECOND account, and their listings quietly vanish.
        MockHttpSession second = new MockHttpSession();
        mockMvc.perform(post("/login/email").param("email", "  PRIYA@Example.COM  ")
                .with(csrf()).session(second));

        // Straight to the dashboard: it found the existing account rather than
        // creating one, which is what skipping the profile step proves.
        mockMvc.perform(post("/login/code").param("code", mail().lastCode())
                        .with(csrf()).session(second))
                .andExpect(redirectedUrl("/dashboard"));

        assertThat(users.count()).isEqualTo(accountsBefore);
        assertThat(userPrivates.findByEmailHash(Hashing.sha256Hex(EMAIL)).orElseThrow().getUserId())
                .isEqualTo(original.getUserId());
    }

    // ---- Failure paths ----------------------------------------------------

    @Test
    @DisplayName("a wrong code re-renders the form with attempts remaining")
    void wrongCodeShowsRemainingAttempts() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login/email").param("email", EMAIL).with(csrf()).session(session));

        String real = mail().lastCode();
        String wrong = real.equals("000000") ? "111111" : "000000";

        MvcResult result = mockMvc.perform(
                        post("/login/code").param("code", wrong).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("not right")
                .contains("4 attempts left");
    }

    @Test
    @DisplayName("the code screen cannot be reached without starting the flow")
    void codeScreenRequiresFlow() throws Exception {
        mockMvc.perform(get("/login/code"))
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/login/profile"))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("a malformed address never reaches the mail sender")
    void invalidAddressSendsNothing() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/login/email").param("email", "not-an-address").with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("does not look like");
        assertThat(mail().count()).isZero();
    }

    @Test
    @DisplayName("a throwaway address is refused before anything is sent")
    void disposableAddressIsRefused() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/login/email").param("email", "someone@mailinator.com").with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("still have later");
        assertThat(mail().count()).isZero();
    }

    @Test
    @DisplayName("the profile step refuses a malformed number without losing the name")
    void badNumberKeepsTheForm() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login/email").param("email", EMAIL).with(csrf()).session(session));
        mockMvc.perform(post("/login/code").param("code", mail().lastCode()).with(csrf()).session(session));

        MvcResult result = mockMvc.perform(post("/login/profile")
                        .param("firstName", "Priya")
                        .param("phone", "5876543210")      // starts with 5
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andReturn();

        String page = result.getResponse().getContentAsString();
        assertThat(page).contains("Indian mobile");
        // Retyping a name you already typed, because a different field was
        // wrong, is the kind of small insult that loses sign-ups.
        assertThat(page).contains("Priya");
    }

    @Test
    @DisplayName("a number already on another account is refused with a message, not a 500")
    void duplicateNumberIsRefusedCleanly() throws Exception {
        signUp(new MockHttpSession());

        // A second person, different address, same number.
        MockHttpSession second = new MockHttpSession();
        mockMvc.perform(post("/login/email").param("email", "someone.else@example.com")
                .with(csrf()).session(second));
        mockMvc.perform(post("/login/code").param("code", mail().lastCode())
                .with(csrf()).session(second));

        MvcResult result = mockMvc.perform(post("/login/profile")
                        .param("firstName", "Rahul")
                        .param("phone", RAW_PHONE)
                        .with(csrf()).session(second))
                .andExpect(status().isOk())
                .andReturn();

        // The interesting part is that this is a 200 with a message. Catching
        // the constraint violation inside @Transactional would not clear the
        // rollback-only mark, and the commit would fail with
        // UnexpectedRollbackException — a 500 where a sentence belongs.
        assertThat(result.getResponse().getContentAsString())
                .contains("already on another account");

        users.findById(userPrivates.findByEmailHash(
                        Hashing.sha256Hex("someone.else@example.com"))
                .orElseThrow().getUserId())
                .ifPresent(u -> users.deleteById(u.getId()));
    }

    // ---- Privacy ----------------------------------------------------------

    @Test
    @DisplayName("no page in the flow renders the full address")
    void flowNeverRendersTheFullAddress() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/login/email").param("email", EMAIL).with(csrf()).session(session));

        String codePage = mockMvc.perform(get("/login/code").session(session))
                .andReturn().getResponse().getContentAsString();

        // The screen must show WHICH address, or a typo is undetectable — but
        // masked, so a glance at the screen does not hand it over.
        assertThat(codePage).contains("example.com");
        assertThat(codePage).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("the signed-in principal carries neither address nor number")
    void principalHoldsNoContactDetails() throws Exception {
        MockHttpSession session = new MockHttpSession();
        signUp(session);

        String dashboard = mockMvc.perform(get("/dashboard").session(session))
                .andReturn().getResponse().getContentAsString();

        // AuthenticatedUser is serialised into SPRING_SESSION_ATTRIBUTES on
        // every request, so a contact detail in the principal would be one
        // written to the database continuously.
        assertThat(dashboard).doesNotContain("9876543210");
        assertThat(dashboard).doesNotContain(EMAIL);
    }

    // ---- Helpers ----------------------------------------------------------

    /** Walks the whole flow, leaving the session signed in. */
    private void signUp(MockHttpSession session) throws Exception {
        mockMvc.perform(post("/login/email").param("email", EMAIL).with(csrf()).session(session));
        mockMvc.perform(post("/login/code").param("code", mail().lastCode())
                .with(csrf()).session(session));
        mockMvc.perform(post("/login/profile")
                .param("firstName", "Priya")
                .param("phone", RAW_PHONE)
                .with(csrf()).session(session));
    }

}
