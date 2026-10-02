package com.nexttenant.seo;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Turns a display name into the URL segment stored in {@code areas.slug}.
 *
 * <p>This must agree, character for character, with the SQL expression in
 * {@code V20__seo_pages.sql} that populated that column — they are two
 * implementations of one rule, and a disagreement means a link built here
 * points at a URL the lookup cannot resolve.
 * {@code LandingPageTest#slugRulesMatch} pins the two against each other, row
 * by row, over the real table.
 *
 * <p>It is used only for building links to a page whose slug is already known
 * to exist. Nothing RESOLVES a request by slugifying input: a request carries
 * the slug, and the slug is looked up. That direction is deliberate — were
 * matching done by slugifying the stored names on each request, changing this
 * method would silently 404 every indexed URL.
 */
public final class Slugs {

    private Slugs() {
    }

    /** "Bandra West" to "bandra-west". */
    public static String of(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return Normalizer.normalize(name, Normalizer.Form.NFD)
                // Decompose and drop the accent marks, so a name with one in it
                // becomes a plain ASCII slug rather than a percent-encoded
                // mess. No Mumbai locality needs this today; the day one does,
                // the alternative is a URL reading %C3%A9.
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
