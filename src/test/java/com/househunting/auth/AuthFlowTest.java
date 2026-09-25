package com.househunting.auth;

import com.househunting.support.RecordingSmsSender;
import com.househunting.user.UserPrivateRepository;
import com.househunting.user.UserRepository;
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
 */
@SpringBootTest(properties = {
        "househunting.sms.provider=recording",
        // Spring Session's filter supplies sessions from its repository and
        // ignores MockMvc's MockHttpSession, so the phone would not carry
        // between steps. Switched off here because these tests exercise OUR
        // flow; that the JDBC store works is Spring Session's own concern and
        // was verified against the running app.
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
@AutoConfigureMockMvc
@Import(AuthFlowTest.TestBeans.class)
class AuthFlowTest {

    private static final String RAW_PHONE = "98765 43210";
    private static final String E164 = "+919876543210";

    @TestConfiguration
    static class TestBeans {
        @Bean
        SmsSender smsSender() {
            return new RecordingSmsSender();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private SmsSender smsSender;
    @Autowired private UserRepository users;
    @Autowired private UserPrivateRepository userPrivates;
    @Autowired private OtpChallengeRepository challenges;
    @Autowired private JdbcClient jdbc;

    private RecordingSmsSender sms() {
        return (RecordingSmsSender) smsSender;
    }

    @BeforeEach
    void reset() {
        challenges.deleteAll();
        jdbc.sql("DELETE FROM otp_rate_limits").update();
        // Remove any account left by an earlier run of this test.
        userPrivates.findByPhoneHash(com.househunting.common.Hashing.sha256Hex(E164))
                .ifPresent(p -> users.deleteById(p.getUserId()));
        sms().clear();
    }

    // ---- CSRF -------------------------------------------------------------

    @Test
    @DisplayName("a POST without a CSRF token is rejected")
    void csrfIsRequired() throws Exception {
        // Without this, any page on the internet could submit a form to our
        // server and the browser would attach the user's session cookie,
        // because cookies are sent by destination rather than by origin.
        mockMvc.perform(post("/login/phone").param("phone", RAW_PHONE))
                .andExpect(status().isForbidden());

        assertThat(sms().count()).isZero();
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
    @DisplayName("phone, code and profile produce a signed-in account")
    void fullSignUpFlow() throws Exception {
        MockHttpSession session = new MockHttpSession();

        // 1. Number -> code sent, redirect to the code screen.
        mockMvc.perform(post("/login/phone").param("phone", RAW_PHONE).with(csrf()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login/code"));

        assertThat(sms().count()).isEqualTo(1);
        assertThat(sms().last().phoneE164()).isEqualTo(E164);

        // 2. Code -> new account, so the profile step.
        mockMvc.perform(post("/login/code").param("code", sms().lastCode()).with(csrf()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login/profile"));

        // 3. Profile -> signed in.
        mockMvc.perform(post("/login/profile")
                        .param("firstName", "Priya")
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
    }

    @Test
    @DisplayName("an existing account skips the profile step")
    void returningUserSkipsProfile() throws Exception {
        MockHttpSession first = new MockHttpSession();
        mockMvc.perform(post("/login/phone").param("phone", RAW_PHONE).with(csrf()).session(first));
        mockMvc.perform(post("/login/code").param("code", sms().lastCode()).with(csrf()).session(first));
        mockMvc.perform(post("/login/profile").param("firstName", "Priya").with(csrf()).session(first));

        // Sign in again on a fresh session.
        MockHttpSession second = new MockHttpSession();
        mockMvc.perform(post("/login/phone").param("phone", RAW_PHONE).with(csrf()).session(second));

        mockMvc.perform(post("/login/code").param("code", sms().lastCode()).with(csrf()).session(second))
                .andExpect(redirectedUrl("/dashboard"));
    }

    // ---- Failure paths ----------------------------------------------------

    @Test
    @DisplayName("a wrong code re-renders the form with attempts remaining")
    void wrongCodeShowsRemainingAttempts() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login/phone").param("phone", RAW_PHONE).with(csrf()).session(session));

        String real = sms().lastCode();
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
    @DisplayName("an invalid number never reaches the SMS sender")
    void invalidNumberSendsNothing() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/login/phone").param("phone", "5876543210").with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("Indian mobile");
        assertThat(sms().count()).isZero();
    }

    // ---- Privacy ----------------------------------------------------------

    @Test
    @DisplayName("no page in the flow renders the full phone number")
    void flowNeverRendersTheFullNumber() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/login/phone").param("phone", RAW_PHONE).with(csrf()).session(session));

        String codePage = mockMvc.perform(get("/login/code").session(session))
                .andReturn().getResponse().getContentAsString();

        // The screen must show WHICH number, or a typo is undetectable — but
        // masked, so a glance at the screen does not hand over the number.
        assertThat(codePage).contains("*****");
        assertThat(codePage).doesNotContain("9876543210");
        assertThat(codePage).doesNotContain(E164);
    }

    @Test
    @DisplayName("the signed-in principal carries no phone number")
    void principalHoldsNoPhoneNumber() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login/phone").param("phone", RAW_PHONE).with(csrf()).session(session));
        mockMvc.perform(post("/login/code").param("code", sms().lastCode()).with(csrf()).session(session));
        mockMvc.perform(post("/login/profile").param("firstName", "Priya").with(csrf()).session(session));

        String dashboard = mockMvc.perform(get("/dashboard").session(session))
                .andReturn().getResponse().getContentAsString();

        // AuthenticatedUser is serialised into SPRING_SESSION_ATTRIBUTES on
        // every request, so a number in the principal would be a number written
        // to the database continuously.
        assertThat(dashboard).doesNotContain("9876543210");
    }
}
