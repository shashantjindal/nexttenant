package com.nexttenant.auth;

import com.nexttenant.mail.EmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a visitor sees when the mail provider refuses the message.
 *
 * <p>Found in production on the first real sign-in attempt: the send threw,
 * nothing caught it, and the browser got Spring's Whitelabel page — "There was
 * an unexpected error (type=Internal Server Error, status=500)". That tells the
 * person nothing, and reads as a broken site rather than a mail provider that
 * needs configuring.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "nexttenant.mail.provider=failing",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
@AutoConfigureMockMvc
@Import(OtpDeliveryFailureTest.TestBeans.class)
class OtpDeliveryFailureTest {

    /** Stands in for every way a provider says no. */
    @TestConfiguration
    static class TestBeans {
        @Bean
        EmailSender emailSender() {
            return (to, subject, body) -> {
                throw new MailSendException("554 Message rejected: sender not verified");
            };
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private OtpChallengeRepository challenges;

    @Test
    @DisplayName("a refused send is a message on the form, not a 500")
    void deliveryFailureIsShownToThePerson() throws Exception {
        MvcResult result = mockMvc.perform(post("/login/email")
                        .param("email", "someone@example.com").with(csrf()))
                .andExpect(status().isOk())
                .andReturn();

        String page = result.getResponse().getContentAsString();

        // Readable, and suggests the one useful action.
        assertThat(page).contains("could not send your code");
        assertThat(page).contains("try again");

        // And NOT the Whitelabel page.
        assertThat(page).doesNotContain("Whitelabel");
        assertThat(page).doesNotContain("Internal Server Error");
    }

    @Test
    @DisplayName("the address is echoed back so it need not be retyped")
    void theTypedAddressSurvives() throws Exception {
        String page = mockMvc.perform(post("/login/email")
                        .param("email", "someone@example.com").with(csrf()))
                .andReturn().getResponse().getContentAsString();

        // Small, and the difference between retrying and giving up.
        assertThat(page).contains("someone@example.com");
    }

    @Test
    @DisplayName("nothing about the provider's refusal reaches the browser")
    void theProviderErrorIsNotLeaked() throws Exception {
        String page = mockMvc.perform(post("/login/email")
                        .param("email", "someone@example.com").with(csrf()))
                .andReturn().getResponse().getContentAsString();

        // "sender not verified" is true, useful to whoever runs the site, and
        // an invitation to probe for anybody else. It belongs in the log.
        assertThat(page).doesNotContain("554");
        assertThat(page).doesNotContain("not verified");
        assertThat(page).doesNotContain("MailSendException");
    }

    @Test
    @DisplayName("a challenge that was never delivered is rolled back")
    void noChallengeSurvivesAFailedSend() throws Exception {
        long before = challenges.count();

        mockMvc.perform(post("/login/email")
                .param("email", "rollback@example.com").with(csrf()));

        // The send happens inside the transaction that creates the challenge,
        // so a throw undoes it. Leaving one behind would be worse than the
        // error: the person has no code, and a resend attempt has been spent
        // on a message that was never sent.
        assertThat(challenges.count()).isEqualTo(before);
    }
}
