package com.nexttenant.auth;

import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.listing.ListingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asking about a room while signed out.
 *
 * <p>Two separate faults met here, and the first hid the second.
 *
 * <p>{@code SecurityConfig} had {@code /flats/**} as permitAll, which also
 * matched {@code /flats/{id}/request}. So a signed-out visitor reached the
 * handler, which reads {@code @AuthenticationPrincipal} and dereferenced it:
 * a NullPointerException, served as a blank white page, at the exact moment
 * somebody was trying to ask about a room.
 *
 * <p>Underneath that, login always ended at {@code /dashboard}. Spring
 * Security saves the request that triggered the login, and with a form login
 * it would also replay it — but this app authenticates by OTP across three
 * screens, so nothing read it back. Fixing only the first fault would have
 * turned a white page into signing in and arriving somewhere else, with the
 * room gone from the screen and from memory.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnToWhereYouWereTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ListingRepository listings;

    private Listing aLiveListing() {
        return listings.findByStatusOrderByPublishedAtDesc(
                ListingStatus.LIVE, PageRequest.of(0, 1)).getContent().getFirst();
    }

    @Test
    @DisplayName("asking while signed out goes to the login screen, not a blank page")
    void signedOutIsSentToLogIn() throws Exception {
        mockMvc.perform(get("/flats/" + aLiveListing().getId() + "/request"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("reporting a listing while signed out does the same")
    void reportingIsAlsoProtected() throws Exception {
        // Same shape, same hole: it sits under /flats/** and reads the
        // principal. Fixing one and not the other would have left the bug
        // alive on the page nobody tests by hand.
        mockMvc.perform(get("/flats/" + aLiveListing().getId() + "/report"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("browsing is still public — only asking needs an account")
    void browsingStaysOpen() throws Exception {
        // The whole point of the permitAll that caused this: requiring a login
        // to look at rooms would cost the organic search traffic the product
        // depends on. The fix had to be narrow.
        mockMvc.perform(get("/flats")).andExpect(status().isOk());
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }
}
