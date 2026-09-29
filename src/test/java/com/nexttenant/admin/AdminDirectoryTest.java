package com.nexttenant.admin;

import com.nexttenant.auth.AuthenticatedUser;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin account and listing browser.
 *
 * <p>Two things are worth testing here and they are not the same thing. One is
 * that nobody but an admin reaches these pages at all. The other is what an
 * admin sees when they do — because this is the one screen in the application
 * that shows private contact details in bulk, and the rule that the full phone
 * number never reaches the HTML is the kind of rule that survives exactly as
 * long as somebody is checking it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminDirectoryTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AdminDirectory directory;
    @Autowired private UserRepository users;
    @Autowired private UserPrivateRepository userPrivates;

    /** A real Indian mobile, so PhoneNumbers accepts it and mask() can act. */
    private static final String PHONE = "+919876543210";

    private User admin;
    private User ordinary;

    @BeforeEach
    void setUp() {
        admin = users.saveAndFlush(new User("Zubin", Gender.MALE, AgeBracket.AGE_23_27));
        userPrivates.saveAndFlush(new UserPrivate(admin.getId(), "zubin@example.com"));

        ordinary = users.saveAndFlush(new User("Farah", Gender.FEMALE, AgeBracket.AGE_28_32));
        userPrivates.saveAndFlush(new UserPrivate(ordinary.getId(), "farah@example.com", PHONE));
    }

    private static Authentication signedIn(User user, boolean isAdmin) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(user.getId(), user.getFirstName(), isAdmin),
                null, List.of(new SimpleGrantedAuthority(
                        isAdmin ? "ROLE_ADMIN" : "ROLE_USER")));
    }

    // ---- Access ------------------------------------------------------------

    @Test
    @DisplayName("an admin reaches both pages")
    void adminSeesThem() throws Exception {
        mockMvc.perform(get("/admin/users").with(authentication(signedIn(admin, true))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/listings").with(authentication(signedIn(admin, true))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an ordinary signed-in user reaches neither")
    void ordinaryUsersAreRefused() throws Exception {
        // Neither controller carries an authorisation annotation. This is what
        // proves SecurityConfig's /admin/** rule is what stops them — so the
        // next route added under that prefix is protected before anybody
        // remembers to protect it.
        mockMvc.perform(get("/admin/users").with(authentication(signedIn(ordinary, false))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/listings").with(authentication(signedIn(ordinary, false))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a signed-out visitor is sent to log in")
    void signedOutIsRedirected() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ---- What reaches the page --------------------------------------------

    @Test
    @DisplayName("the full phone number never reaches the HTML")
    void phoneNumbersAreMasked() throws Exception {
        String html = mockMvc.perform(get("/admin/users")
                        .with(authentication(signedIn(admin, true))))
                .andReturn().getResponse().getContentAsString();

        // The whole point of the screen: the operator can see there IS a
        // number and recognise it, and cannot dial it from here.
        assertThat(html).doesNotContain(PHONE);
        assertThat(html).doesNotContain("9876543210");
        assertThat(html).contains("+91 ***** 43210");
    }

    @Test
    @DisplayName("the masking happens in the service, not the template")
    void maskingIsNotATemplateConcern() {
        // Asserted against the record rather than the HTML because this is
        // where the guarantee actually lives. A template could be rewritten
        // tomorrow; if the full number is not in the row, no rewrite can leak
        // it.
        AdminDirectory.AccountRow row = directory.accounts("farah@example.com", 0).getFirst();

        assertThat(row.maskedPhone()).isEqualTo("+91 ***** 43210");
        assertThat(row.maskedPhone()).doesNotContain("98765");
    }

    @Test
    @DisplayName("an account with no phone shows nothing rather than an empty mask")
    void accountsWithoutAPhone() {
        // Normal since V14: email login means an account may never have had a
        // number. PhoneNumbers.mask returns "" for that, which would render as
        // a blank cell reading like a fault.
        AdminDirectory.AccountRow row = directory.accounts("zubin@example.com", 0).getFirst();

        assertThat(row.maskedPhone()).isNull();
    }

    @Test
    @DisplayName("email is shown in full, because it is what identifies the account")
    void emailIsNotMasked() throws Exception {
        String html = mockMvc.perform(get("/admin/users")
                        .with(authentication(signedIn(admin, true))))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("farah@example.com");
    }

    // ---- Search ------------------------------------------------------------

    @Test
    @DisplayName("search narrows by email and by first name")
    void searchMatchesBoth() {
        assertThat(directory.accounts("farah@example", 0))
                .extracting(AdminDirectory.AccountRow::firstName)
                .containsExactly("Farah");

        assertThat(directory.accounts("zubin", 0))
                .extracting(AdminDirectory.AccountRow::firstName)
                .containsExactly("Zubin");
    }

    @Test
    @DisplayName("search does not turn a phone number into an account")
    void searchIgnoresPhoneNumbers() {
        // Deliberate. A box that answers "whose number is this" is a lookup
        // service for anybody who reaches this page, and an operator arriving
        // from a report already has an account or a listing to work from.
        assertThat(directory.accounts("9876543210", 0)).isEmpty();
    }

    @Test
    @DisplayName("a blank search is the same as no search")
    void blankSearchListsEverybody() {
        // The form submits q= when the box is cleared, so this is the ordinary
        // path back to the full list, not an edge case.
        assertThat(directory.accounts("   ", 0)).hasSizeGreaterThanOrEqualTo(2);
        assertThat(directory.accounts(null, 0)).hasSizeGreaterThanOrEqualTo(2);
    }

    // ---- Listings ----------------------------------------------------------

    @Test
    @DisplayName("an unknown status returns nothing rather than everything")
    void unknownStatusIsNotIgnored() {
        // The filter comes from a query parameter, so somebody will eventually
        // type one that does not exist. Falling back to "no filter" would
        // quietly show the whole table and look like the filter had worked.
        assertThat(directory.listings("NONSENSE", 0)).isEmpty();
    }

    @Test
    @DisplayName("paging past the end is empty, not an error")
    void pagingPastTheEnd() {
        assertThat(directory.accounts(null, 1_000)).isEmpty();
    }
}
