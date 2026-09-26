package com.househunting.workemail;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalises and vets a work email address.
 *
 * <p><b>What the badge is claiming.</b> "Work email verified" says: this person
 * controls a mailbox at an organisation that had to register a domain. That is
 * the entire content of the signal, and the only thing making it worth anything
 * is that a broker cannot mint fifty of them in an afternoon. A free mailbox
 * takes ninety seconds to create, so accepting gmail.com would reduce the badge
 * to "this person owns a browser".
 *
 * <p><b>What it is NOT claiming.</b> Not that they work there now, not that the
 * company is real, not that they are who they say. A domain costs a few hundred
 * rupees. This is a speed bump, sized to the problem: it makes bulk fake
 * accounts annoying, which is enough, and anything stronger (a payslip, an ID)
 * costs more trust than it buys on a flat-sharing site.
 *
 * <p>Pure static methods and no Spring: every decision here is a function of
 * one string, and a class with no dependencies is a class whose tests need no
 * context to start.
 */
public final class WorkEmailAddresses {

    /**
     * Deliberately loose.
     *
     * <p>RFC 5322 permits quoted strings, comments and nested parentheses in a
     * local part; a regex that implements it is famously several thousand
     * characters and still not quite right. It does not matter here, because
     * the real validation is that a code has to arrive at the address. This
     * check exists to catch typos early and to refuse obviously malformed input
     * before we hand it to a mail provider.
     */
    private static final Pattern SHAPE =
            Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    /** Longer than any real address anybody will type; guards the columns. */
    public static final int MAX_LENGTH = 254;

    /**
     * Consumer mailbox providers, matched on the whole domain.
     *
     * <p>Not exhaustive and never will be — there are thousands of free
     * providers and a list of them is a maintenance burden with no end. It
     * covers what people in India actually use, which is where the volume is.
     */
    private static final Set<String> FREE_DOMAINS = Set.of(
            "gmail.com", "googlemail.com",
            "yahoo.com", "yahoo.in", "yahoo.co.in", "ymail.com", "rocketmail.com",
            "outlook.com", "outlook.in", "hotmail.com", "hotmail.co.uk",
            "live.com", "live.in", "msn.com",
            "icloud.com", "me.com", "mac.com",
            "aol.com", "mail.com", "gmx.com", "gmx.net",
            "protonmail.com", "proton.me", "pm.me", "tutanota.com",
            "rediffmail.com", "rediff.com", "indiatimes.com", "sify.com",
            "yandex.com", "yandex.ru", "zohomail.com",
            "fastmail.com", "hey.com", "inbox.com", "disroot.org");

    /**
     * Brands whose country domains are too numerous to list.
     *
     * <p>Matched as {@code brand.<anything>} — yahoo.co.uk, outlook.fr,
     * hotmail.de. The cost of this shortcut is that a company genuinely called
     * "Live Technologies" at live.tech would be refused. That is a real false
     * positive, it will be rare, and the support cost of one person mailing us
     * is far below the cost of a free-mailbox loophole.
     */
    private static final Set<String> FREE_BRANDS = Set.of(
            "gmail", "yahoo", "ymail", "outlook", "hotmail", "live", "msn",
            "icloud", "aol", "gmx", "yandex", "rediffmail");

    private WorkEmailAddresses() {
    }

    /**
     * Trims and lowercases.
     *
     * <p>Lowercasing the LOCAL part too, which strictly speaking the standard
     * says is the mail server's business. Every provider anybody uses treats it
     * case-insensitively, and the alternative is that Asha@corp.com and
     * asha@corp.com are two different accounts as far as the uniqueness index
     * is concerned — which would reopen the one-mailbox-many-badges hole the
     * index exists to close.
     *
     * <p>{@link Locale#ROOT} and not the default locale: in a Turkish locale
     * {@code "I".toLowerCase()} is "ı", not "i", and the address would stop
     * matching itself depending on where the server happens to run.
     */
    public static String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** The part after the @, or empty if there is not exactly one. */
    public static String domainOf(String normalised) {
        int at = normalised.lastIndexOf('@');
        return at < 0 ? "" : normalised.substring(at + 1);
    }

    /**
     * Checks shape, length and provider, throwing with a message meant to be
     * shown to the person who typed it.
     *
     * @return the normalised address
     */
    public static String validate(String raw) {
        String address = normalise(raw);

        if (address.isEmpty()) {
            throw new InvalidWorkEmailException("Enter your work email address.");
        }
        if (address.length() > MAX_LENGTH) {
            throw new InvalidWorkEmailException("That address is too long.");
        }
        if (!SHAPE.matcher(address).matches()) {
            throw new InvalidWorkEmailException("That does not look like an email address.");
        }
        if (isFreeProvider(domainOf(address))) {
            // Says what to do, not just what is wrong. "Invalid email" here
            // would leave somebody retyping their Gmail address three times.
            throw new InvalidWorkEmailException(
                    "Use your company address, not a personal one — that is what makes "
                            + "the badge worth anything.");
        }
        return address;
    }

    public static boolean isFreeProvider(String domain) {
        if (domain.isEmpty()) {
            return false;
        }
        if (FREE_DOMAINS.contains(domain)) {
            return true;
        }
        int firstDot = domain.indexOf('.');
        return firstDot > 0 && FREE_BRANDS.contains(domain.substring(0, firstDot));
    }

    /**
     * "a•••a@corp.com" — for showing which address a code went to.
     *
     * <p>The page has to name the address, or somebody who typed it wrong has
     * no way to notice. It must not print it in full, because a support
     * screenshot or a shoulder-glance would then carry a colleague's address.
     * The domain stays whole: it is the part that identifies the employer, the
     * person already knows it, and hiding it would make the line useless.
     */
    public static String mask(String address) {
        int at = address.lastIndexOf('@');
        if (at < 1) {
            return address;
        }
        String local = address.substring(0, at);
        String domain = address.substring(at);

        if (local.length() <= 2) {
            return "*".repeat(local.length()) + domain;
        }
        return local.charAt(0) + "*".repeat(local.length() - 2)
                + local.charAt(local.length() - 1) + domain;
    }
}
