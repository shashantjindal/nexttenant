package com.nexttenant.search;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * A position in the result set, for keyset pagination.
 *
 * <p>Holds the sort value and id of the LAST row on the page just shown, so the
 * next page can seek directly to "everything after this row" instead of
 * counting rows to skip.
 *
 * <p>Why not OFFSET: {@code OFFSET 10000} makes PostgreSQL produce ten thousand
 * rows and throw them away, so page 500 costs five hundred times page one.
 * Keyset uses the index to jump straight to the position, so every page costs
 * the same.
 *
 * <p>OFFSET has a second, subtler problem: it paginates over a moving target.
 * If a new listing is published while someone is reading page one, page two
 * begins one row earlier than it should and a listing is skipped entirely — it
 * simply never appears. Keyset is anchored to a row, so an insertion elsewhere
 * cannot shift the window.
 *
 * @param sortValue the sort column's value on the last row, as text
 * @param id        that row's id, breaking ties when sort values are equal
 */
public record SearchCursor(String sortValue, UUID id) {

    private static final String SEPARATOR = "|";

    /**
     * Base64 for the URL. Not encryption and not a secret — it just stops the
     * parameter looking like something to hand-edit, and keeps the separator
     * out of the query string.
     */
    public String encode() {
        String raw = sortValue + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns empty for anything unparseable, so a mangled cursor shows page
     * one rather than a 500. Cursors arrive from URLs people share and edit.
     */
    public static Optional<SearchCursor> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int split = raw.lastIndexOf(SEPARATOR);
            if (split <= 0) {
                return Optional.empty();
            }
            return Optional.of(new SearchCursor(
                    raw.substring(0, split),
                    UUID.fromString(raw.substring(split + 1))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
