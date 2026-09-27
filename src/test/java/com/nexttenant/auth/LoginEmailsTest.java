package com.nexttenant.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Normalisation, validation and masking of the sign-in address.
 *
 * <p>No Spring context: every method here is a pure function of a string, and a
 * test that needs no context starts in milliseconds and therefore gets run.
 */
class LoginEmailsTest {

    // ---- Normalisation ----------------------------------------------------

    @Test
    @DisplayName("one address has exactly one representation")
    void normalisationCollapsesCaseAndWhitespace() {
        // Without this, each of these is a SEPARATE account for one person, and
        // "where did my listings go?" is the support ticket.
        assertThat(LoginEmails.validate("  Priya@Example.COM  ")).isEqualTo("priya@example.com");
        assertThat(LoginEmails.validate("PRIYA@EXAMPLE.COM")).isEqualTo("priya@example.com");
    }

    @Test
    @DisplayName("the domain is read from the LAST @, not the first")
    void domainReadsFromTheLastAt() {
        // Local parts may legally contain an @ when quoted. Splitting on the
        // first one would read the domain as part of the local part and let a
        // disposable provider through the blocklist.
        assertThat(LoginEmails.domainOf("odd@name@mailinator.com")).isEqualTo("mailinator.com");
    }

    // ---- Validation -------------------------------------------------------

    @Test
    @DisplayName("free providers are accepted — this is not the work-email check")
    void freeProvidersAreAllowed() {
        // The single most important assertion in this class. WorkEmailAddresses
        // REJECTS these, because a badge that a Gmail account can earn is not a
        // badge. Sign-in must do the opposite, or nearly every user is locked
        // out on their first visit. Same subject, opposite rule, two classes.
        assertThat(LoginEmails.validate("someone@gmail.com")).isEqualTo("someone@gmail.com");
        assertThat(LoginEmails.validate("someone@outlook.com")).isEqualTo("someone@outlook.com");
        assertThat(LoginEmails.validate("someone@yahoo.in")).isEqualTo("someone@yahoo.in");
        assertThat(LoginEmails.validate("someone@rediffmail.com")).isEqualTo("someone@rediffmail.com");
    }

    @Test
    @DisplayName("malformed addresses are refused before anything is sent")
    void malformedAddressesAreRefused() {
        assertThatThrownBy(() -> LoginEmails.validate("not-an-address"))
                .isInstanceOf(InvalidLoginEmailException.class);        // no @

        assertThatThrownBy(() -> LoginEmails.validate("no@domain"))
                .isInstanceOf(InvalidLoginEmailException.class);        // no dot

        assertThatThrownBy(() -> LoginEmails.validate("two spaces@example.com"))
                .isInstanceOf(InvalidLoginEmailException.class);

        assertThatThrownBy(() -> LoginEmails.validate(""))
                .isInstanceOf(InvalidLoginEmailException.class);

        assertThatThrownBy(() -> LoginEmails.validate(null))
                .isInstanceOf(InvalidLoginEmailException.class);
    }

    @Test
    @DisplayName("an over-long address is refused rather than truncated")
    void overLongAddressIsRefused() {
        // 254 is RFC 5321's limit and the column width. Truncating instead of
        // refusing would silently sign somebody in as a different address.
        String tooLong = "a".repeat(250) + "@example.com";

        assertThatThrownBy(() -> LoginEmails.validate(tooLong))
                .isInstanceOf(InvalidLoginEmailException.class)
                .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("throwaway providers are refused, with a message that says why")
    void disposableProvidersAreRefused() {
        // Under phone login an account cost a real SIM. Under email login it
        // costs nothing, so the laziest way to farm accounts is a throwaway
        // inbox. A speed bump, not a wall — see the note on DISPOSABLE_DOMAINS.
        assertThatThrownBy(() -> LoginEmails.validate("someone@mailinator.com"))
                .isInstanceOf(InvalidLoginEmailException.class)
                .hasMessageContaining("still have later");

        assertThatThrownBy(() -> LoginEmails.validate("SOMEONE@YOPMAIL.COM"))
                .isInstanceOf(InvalidLoginEmailException.class);
    }

    @Test
    @DisplayName("the refusal message never repeats the address back")
    void messagesOmitTheAddress() {
        // Exception messages reach log files, and an address in a log file is
        // the leak this whole split exists to prevent.
        assertThatThrownBy(() -> LoginEmails.validate("private.person@mailinator.com"))
                .hasMessageNotContaining("private.person");
    }

    // ---- Masking ----------------------------------------------------------

    @Test
    @DisplayName("masking shows enough to recognise and not enough to use")
    void maskingHidesTheLocalPart() {
        String masked = LoginEmails.mask("priya@example.com");

        // First and last character survive so a typo is spottable.
        assertThat(masked).startsWith("p").contains("@example.com").endsWith("example.com");
        assertThat(masked).doesNotContain("priya");
    }

    @Test
    @DisplayName("the domain stays whole, because that is the part people check")
    void maskingKeepsTheDomain() {
        // "I typed gmial" is the most common way this goes wrong, and hiding
        // the domain would make the line on the screen useless for catching it.
        assertThat(LoginEmails.mask("someone@gmail.com")).endsWith("@gmail.com");
    }

    @Test
    @DisplayName("a very short local part is hidden entirely rather than exposed")
    void maskingHandlesShortLocalParts() {
        // "ab" cannot show a first AND last character without showing both, so
        // the rule that normally hides the middle would hide nothing at all.
        assertThat(LoginEmails.mask("ab@example.com")).isEqualTo("**@example.com");
        assertThat(LoginEmails.mask("a@example.com")).isEqualTo("*@example.com");
    }

    @Test
    @DisplayName("masking never throws on rubbish")
    void maskingToleratesRubbish() {
        // It runs on whatever is in the session, and a page that 500s because a
        // mask failed would be a worse bug than a blank line.
        assertThat(LoginEmails.mask(null)).isEmpty();
        assertThat(LoginEmails.mask("no-at-sign")).isEmpty();
        assertThat(LoginEmails.mask("@example.com")).isEmpty();
    }
}
