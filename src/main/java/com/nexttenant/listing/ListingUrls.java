package com.nexttenant.listing;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

/**
 * The public address of a listing.
 *
 * <p>A listing used to live at {@code /flats/<uuid>}, which is correct, unique,
 * and tells a reader nothing. It costs twice: a link pasted into WhatsApp says
 * only that it is a link, and a search engine is handed a URL containing none
 * of the words anybody searches for. "Bandra West" and "2 BHK" are exactly what
 * people type, and a URL is one of the few places we can say them.
 *
 * <p>So the canonical form is {@code /flats/bandra-west-2-bhk-private-room-<uuid>}.
 *
 * <h2>Why the id stays</h2>
 *
 * <p>The obvious design is a pure slug — {@code /flats/bandra-west-2-bhk} —
 * and it brings two problems this one does not have. It needs uniqueness, so a
 * second 2 BHK in Bandra West becomes {@code -2}, which means a stored column,
 * a collision loop and a number in the URL that means nothing to anybody. And
 * a listing's area or configuration can be edited, so the slug either goes
 * stale or the address changes under links people have already shared.
 *
 * <p>Here the UUID remains the identity and the words are decoration. Nothing
 * needs storing, nothing can collide, and an edited listing simply gets a new
 * canonical address while every link ever shared still resolves.
 *
 * <h2>Why the id goes last</h2>
 *
 * <p>Two reasons, and both matter. A UUID is exactly 36 characters, so
 * {@link #idFrom} is "the last 36 characters" — one rule that reads both the
 * new form and the bare {@code /flats/<uuid>} links already shared and indexed,
 * with no parsing of hyphens that a UUID is full of anyway. And browsers and
 * chat apps truncate the end of a long URL, so the words survive where the
 * identifier would not.
 */
public final class ListingUrls {

    /** A UUID in its canonical text form is always this long. */
    private static final int ID_LENGTH = 36;

    /**
     * How much slug to keep.
     *
     * <p>Long enough for "bandra kurla complex 2 bhk private room", short
     * enough that the address stays readable. Search engines have not weighted
     * keywords in a URL heavily for years; this is about a person glancing at
     * a link, and past a point more words make that worse rather than better.
     */
    private static final int MAX_SLUG = 50;

    private ListingUrls() {
    }

    /**
     * The one address this listing should be reached at.
     *
     * <p>Arguments are the display labels — "Bandra West", "2 BHK", "Private
     * room" — and any of them may be null, which is normal for a draft that
     * has not been through the whole wizard. A listing with nothing filled in
     * yet is simply {@code /flats/<uuid>}, which is still canonical for it.
     */
    public static String path(UUID id, String area, String configuration, String roomType) {
        String slug = slugify(join(area, configuration, roomType));
        return slug.isEmpty()
                ? "/flats/" + id
                : "/flats/" + slug + "-" + id;
    }

    /**
     * Reads the id back out of a path segment.
     *
     * <p>Accepts both forms, because the bare one is already in inboxes and in
     * Google's index and must keep working.
     *
     * @throws IllegalArgumentException if there is no UUID on the end, which
     *                                  the controller turns into a 404
     */
    public static UUID idFrom(String segment) {
        if (segment == null || segment.length() < ID_LENGTH) {
            throw new IllegalArgumentException("no id in '" + segment + "'");
        }
        return UUID.fromString(segment.substring(segment.length() - ID_LENGTH));
    }

    /**
     * Turns display text into URL-safe words.
     *
     * <p>The normalise step matters more than it looks: without it an accented
     * character survives into the path and gets percent-encoded by the browser,
     * so a URL meant to be readable arrives as {@code %C3%A9}. Decomposing to
     * base letters and dropping the marks keeps it plain ASCII.
     */
    static String slugify(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String ascii = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        String slug = ascii.toLowerCase(Locale.ROOT)
                // Anything that is not a letter or digit becomes a separator,
                // then runs of separators collapse. Doing it in two steps means
                // "2 BHK / Private room" does not become "2-bhk---private-room".
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");

        if (slug.length() > MAX_SLUG) {
            slug = slug.substring(0, MAX_SLUG);
            // Never end mid-word: cut back to the last separator, so the URL
            // reads "bandra-kurla" rather than "bandra-kurl".
            int lastBreak = slug.lastIndexOf('-');
            if (lastBreak > 0) {
                slug = slug.substring(0, lastBreak);
            }
            slug = slug.replaceAll("-+$", "");
        }

        return slug;
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(part);
            }
        }
        return out.toString();
    }
}
