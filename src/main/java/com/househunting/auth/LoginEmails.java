package com.househunting.auth;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Email addresses used to sign in.
 *
 * <p>The counterpart to {@link PhoneNumbers}: normalise to one representation,
 * reject what cannot be an address, mask for display.
 *
 * <p><b>Deliberately not {@code WorkEmailAddresses}, and deliberately not a
 * flag on it.</b> That class REJECTS gmail, outlook and the rest — refusing a
 * personal address is the entire point of a work-email trust badge. This one
 * must ACCEPT them, because otherwise nearly every user is locked out on their
 * first visit.
 *
 * <p>Same subject, opposite rule. A shared class with an
 * {@code allowFreeProviders} boolean would read fine today and is exactly how
 * somebody later passes the wrong value and silently reopens the badge to
 * anyone with a Gmail account. Two classes cannot be confused by a parameter.
 */
public final class LoginEmails {

    /** RFC 5321's limit on a whole address. */
    public static final int MAX_LENGTH = 254;

    /**
     * A shape check, not a validity check.
     *
     * <p>No regex decides whether an address is deliverable — RFC 5322's real
     * grammar permits things no mail server accepts, and the only honest test
     * is sending to it, which is precisely what the next step does. So this
     * catches typing errors ("no @", a trailing comma, a space) and lets the
     * OTP be the real proof.
     */
    private static final Pattern SHAPE =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    /**
     * Throwaway-mailbox providers.
     *
     * <p><b>Why this exists now and did not before.</b> Under phone login an
     * account cost a real SIM card. Under email login it costs nothing, and a
     * throwaway inbox makes accounts free and unlimited — which matters because
     * an account can publish a listing carrying a phone number.
     *
     * <p><b>What it is not.</b> A speed bump, not a wall. The list rots, new
     * providers appear weekly, and anyone determined walks around it with a
     * cheap domain. The real gate on abuse is elsewhere: verified work email to
     * publish, a cap on live listings per account, and moderation. This just
     * removes the laziest attempt.
     *
     * <p>Kept short on purpose. A long borrowed blocklist eventually contains
     * somebody's real employer, and being told your own address is fake is a
     * far worse failure than letting a throwaway through.
     */
    private static final Set<String> DISPOSABLE_DOMAINS = Set.of(
            "mailinator.com", "guerrillamail.com", "10minutemail.com",
            "tempmail.com", "temp-mail.org", "throwawaymail.com",
            "yopmail.com", "trashmail.com", "sharklasers.com",
            "getnada.com", "dispostable.com", "maildrop.cc");

    private LoginEmails() {
    }

    /**
     * Trimmed and lower-cased — one address, one representation.
     *
     * <p>Without this, {@code Me@Gmail.com} and {@code me@gmail.com} are two
     * accounts for one person, and "one address, one account" quietly stops
     * being true. The same reasoning as E.164 normalisation in
     * {@link PhoneNumbers}.
     *
     * <p>{@link Locale#ROOT}, never the default locale: in a Turkish locale
     * {@code "I".toLowerCase()} is "ı" rather than "i", so an address would
     * stop matching itself depending on where the server happens to run. That
     * is a genuinely miserable bug to find, and the fix is one argument.
     */
    public static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** The part after the last {@code @}, or empty if there is none. */
    public static String domainOf(String normalised) {
        int at = normalised.lastIndexOf('@');
        return at < 0 ? "" : normalised.substring(at + 1);
    }

    /**
     * Checks shape, length and provider.
     *
     * @return the normalised address, ready to hash and to send to
     * @throws InvalidLoginEmailException with a message safe to show the person
     */
    public static String validate(String raw) {
        String address = normalise(raw);

        if (address.isEmpty()) {
            throw new InvalidLoginEmailException("Enter your email address");
        }
        if (address.length() > MAX_LENGTH) {
            throw new InvalidLoginEmailException("That address is too long");
        }
        if (!SHAPE.matcher(address).matches()) {
            throw new InvalidLoginEmailException("That does not look like an email address");
        }
        if (DISPOSABLE_DOMAINS.contains(domainOf(address))) {
            // Names the problem and the remedy. "Invalid email" would leave
            // somebody retyping a working address several times over.
            throw new InvalidLoginEmailException(
                    "Use an address you will still have later — we send your "
                            + "sign-in codes there.");
        }
        return address;
    }

    /**
     * "a•••a@gmail.com" — for the "we sent a code to …" line.
     *
     * <p>The page has to name where the code went, or somebody who mistyped
     * has no way to notice. Printing it whole would put it in every support
     * screenshot and in front of anyone glancing at the screen.
     *
     * <p>The domain stays intact. The person already knows their own provider,
     * so hiding it costs them the ability to spot "I typed gmial", which is the
     * single most common way this goes wrong.
     */
    public static String mask(String address) {
        if (address == null) {
            return "";
        }
        int at = address.lastIndexOf('@');
        if (at < 1) {
            return "";
        }
        String local = address.substring(0, at);
        String domain = address.substring(at);

        // Two characters or fewer cannot show a first and last without showing
        // the whole thing, so they are hidden entirely.
        if (local.length() <= 2) {
            return "*".repeat(local.length()) + domain;
        }
        return local.charAt(0)
                + "*".repeat(local.length() - 2)
                + local.charAt(local.length() - 1)
                + domain;
    }
}
