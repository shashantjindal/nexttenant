package com.nexttenant.admin;

import com.nexttenant.auth.LoginEmails;
import com.nexttenant.auth.PhoneNumbers;
import com.nexttenant.listing.Listing;
import com.nexttenant.listing.wizard.ListingDraftService;
import com.nexttenant.user.AgeBracket;
import com.nexttenant.user.Gender;
import com.nexttenant.user.User;
import com.nexttenant.user.UserAccounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening a listing on somebody else's behalf.
 *
 * <p>Page one of the admin flow, as a service: it turns an email address and a
 * phone number into an account that person can later sign into, and a draft
 * listing already belonging to them.
 *
 * <p><b>The account is created without an OTP, and that is not a hole.</b>
 * Signing in IS the OTP — {@code AuthController} mails a code to the address
 * and nothing else grants a session. So an operator can create an account for
 * priya@example.com and fill in her listing, and still cannot get into it; the
 * first person to sign in as her has to receive mail at her address. What is
 * being asserted here is an address, not access to one.
 *
 * <p>The same is true of the number. {@code completeProfile} records it
 * without setting {@code phone_verified_at}, exactly as the ordinary profile
 * screen does — nobody has proved it either way, and a verified badge that
 * means "an admin typed it" would be a badge that lies.
 */
@Service
public class AdminListingOnboarding {

    private final UserAccounts accounts;
    private final ListingDraftService drafts;

    public AdminListingOnboarding(UserAccounts accounts, ListingDraftService drafts) {
        this.accounts = accounts;
        this.drafts = drafts;
    }

    /** What page one collected, before any of it has been believed. */
    public record Details(String email, String firstName, String phone,
                          Gender gender, AgeBracket ageBracket) {
    }

    /** The account this listing will belong to, and the draft itself. */
    public record Opened(User owner, Listing draft, boolean existingAccount) {
    }

    /**
     * Finds or creates the account, then opens a draft owned by it.
     *
     * <p>Validation runs through the very classes the public sign-in uses —
     * {@link LoginEmails#validate} and {@link PhoneNumbers#normalise} — rather
     * than a looser admin-only version. An operator typing a number wrong is
     * at least as likely as a visitor doing it, and a second set of rules here
     * would mean the admin door could create accounts the front door would
     * have refused.
     *
     * @throws com.nexttenant.auth.InvalidLoginEmailException   bad address
     * @throws com.nexttenant.auth.InvalidPhoneNumberException  bad number
     * @throws com.nexttenant.user.PhoneAlreadyInUseException   number is taken
     * @throws SuspendedAccountException                        account is not usable
     */
    @Transactional
    public Opened open(Details details) {
        String email = LoginEmails.validate(details.email());
        String phoneE164 = PhoneNumbers.normalise(details.phone());

        String firstName = trimToNull(details.firstName());
        if (firstName == null || firstName.length() > 40) {
            throw new IllegalArgumentException(
                    "Enter a first name, up to 40 characters.");
        }

        User owner = accounts.findOrCreate(email);
        boolean existing = owner.getFirstName() != null;

        if (!owner.isActive()) {
            // Suspended or removed. Listing for them would publish a room
            // belonging to an account that is not allowed to answer requests
            // on it — a listing nobody can be connected to.
            throw new SuspendedAccountException(
                    "That account is " + owner.getStatus().name().toLowerCase()
                            + ". Reinstate it before listing for them.");
        }

        /*
         * EXISTING DETAILS WIN.
         *
         * An operator reading a WhatsApp message is a worse source than the
         * person themselves, who typed their own name and received a sign-in
         * code at this address. Silently replacing "Priyanka" with "Priya"
         * because that is how she signs her messages would rewrite what other
         * people see on her listing, and she would never be told.
         *
         * Blanks are filled in, because a blank is not a value somebody chose.
         * Nothing is cleared: passing the stored value back is how a field the
         * operator left empty stays as it was.
         */
        String name = owner.getFirstName() != null ? owner.getFirstName() : firstName;
        Gender gender = owner.getGender() != null ? owner.getGender() : details.gender();
        AgeBracket age = owner.getAgeBracket() != null
                ? owner.getAgeBracket() : details.ageBracket();

        // null means "leave the number alone" — see UserAccounts.completeProfile.
        String phoneToStore = accounts.hasPhone(owner.getId()) ? null : phoneE164;

        User saved = accounts.completeProfile(
                owner.getId(), name, gender, age, phoneToStore);

        /*
         * currentDraft, not a fresh listing every time.
         *
         * One draft per person is the rule the ordinary wizard already works
         * by, and breaking it here would mean an operator who starts the flow
         * twice for the same person leaves a half-finished listing behind that
         * nobody will ever publish. Starting again resumes the same row.
         */
        Listing draft = drafts.currentDraft(saved.getId());

        return new Opened(saved, draft, existing);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Raised when the account exists but may not hold a listing. */
    public static class SuspendedAccountException extends RuntimeException {
        public SuspendedAccountException(String message) {
            super(message);
        }
    }
}
