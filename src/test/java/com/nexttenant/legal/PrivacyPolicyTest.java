package com.nexttenant.legal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The privacy policy.
 *
 * <p>Tested because it makes promises. Several sentences on that page are
 * claims about how this system behaves — that numbers are never on a public
 * page, that photo location data is destroyed, that one cookie exists — and a
 * promise nothing checks is a promise that quietly stops being true.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PrivacyPolicyTest {

    @Autowired private MockMvc mockMvc;

    private String page() throws Exception {
        return mockMvc.perform(get("/privacy"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("it is readable without an account")
    void publicWithoutSigningIn() throws Exception {
        // The one thing that would make it useless. Somebody deciding whether
        // to hand over a phone number reads this BEFORE they have an account,
        // and a policy behind a login is read at no useful moment.
        mockMvc.perform(get("/privacy")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("every page links to it")
    void linkedFromTheFooter() throws Exception {
        // Published means findable. A policy whose URL you must already know
        // is not published, whatever it says.
        String home = mockMvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(home).contains("href=\"/privacy\"");
    }

    @Test
    @DisplayName("it says the things that are the point of it")
    void itCoversWhatItMustCover() throws Exception {
        String html = page();

        assertThat(html)
                .contains("Digital Personal Data Protection Act")
                .contains("Grievance")
                .contains("Data Protection Board of India")
                // The specific, checkable claims about this system.
                .contains("never on a public")
                .contains("re-encoded")
                .contains("SESSION");
    }

    @Test
    @DisplayName("it admits when no grievance officer has been named")
    void itSaysSoWhenIncomplete() throws Exception {
        String html = page();

        // The test profile sets neither, so the page should be owning up. The
        // Act requires a NAMED officer; a policy quietly missing one tells the
        // reader there is somewhere to complain to when there is not.
        assertThat(html).contains("Not yet complete");
        // The right to escalate is NOT inside that branch: it exists whether
        // or not an officer has been named, and matters most when one has not.
        assertThat(html).contains("Data Protection Board of India");
    }

    @Test
    @DisplayName("a named officer and address replace the warning")
    void itShowsThemOnceConfigured() {
        // Asserted on the properties rather than by booting a second context
        // with different environment variables, which would double the slowest
        // part of this suite to check one boolean.
        var unset = new LegalProperties(null, null, null);
        var halfSet = new LegalProperties("privacy@example.com", "   ", null);
        var complete = new LegalProperties("privacy@example.com", "A Name", null);

        assertThat(unset.hasGrievanceOfficer()).isFalse();
        // Half-configured counts as not configured: an inbox is not a person,
        // and the Act asks for a person.
        assertThat(halfSet.hasGrievanceOfficer()).isFalse();
        assertThat(complete.hasGrievanceOfficer()).isTrue();

        assertThat(unset.entityName()).isEqualTo("NextTenant");
    }
}
