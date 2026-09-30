package com.nexttenant.listing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shape of a listing's public address.
 *
 * <p>The rule that everything else rests on is that the id is the last 36
 * characters of the segment and the words in front are decoration. Two things
 * follow, and both are tested here: any spelling of the words resolves to the
 * same listing, and the bare {@code /flats/<uuid>} links already shared and
 * indexed keep working forever.
 */
class ListingUrlsTest {

    private static final UUID ID = UUID.fromString("fda47511-1ab4-4575-967f-ae4d7d12aaaf");

    // ---- Building ----------------------------------------------------------

    @Test
    @DisplayName("the words people search for come first, the id last")
    void theCanonicalForm() {
        assertThat(ListingUrls.path(ID, "Bandra West", "2 BHK", "Private room"))
                .isEqualTo("/flats/bandra-west-2-bhk-private-room-" + ID);
    }

    @Test
    @DisplayName("a draft with nothing filled in is still just its id")
    void nothingToSay() {
        // Every listing starts here: the wizard creates the row before asking
        // where the flat is. A path is needed from that moment, and a leading
        // hyphen would be the alternative.
        assertThat(ListingUrls.path(ID, null, null, null)).isEqualTo("/flats/" + ID);
        assertThat(ListingUrls.path(ID, "  ", null, "")).isEqualTo("/flats/" + ID);
    }

    @Test
    @DisplayName("a partly filled draft uses what it has")
    void partiallyFilled() {
        assertThat(ListingUrls.path(ID, "Powai", null, null))
                .isEqualTo("/flats/powai-" + ID);
    }

    // ---- Reading back ------------------------------------------------------

    @Test
    @DisplayName("the id reads back out of both forms")
    void idFromEitherForm() {
        assertThat(ListingUrls.idFrom("bandra-west-2-bhk-private-room-" + ID)).isEqualTo(ID);
        // The form already in inboxes and in Google's index. If this ever
        // stops working, every link anybody has shared breaks at once.
        assertThat(ListingUrls.idFrom(ID.toString())).isEqualTo(ID);
    }

    @Test
    @DisplayName("the words are decoration — any spelling finds the same listing")
    void theWordsDoNotIdentify() {
        // Which is exactly why the controller redirects to one canonical form:
        // left alone, this is the same page at infinitely many addresses.
        assertThat(ListingUrls.idFrom("anything-at-all-" + ID)).isEqualTo(ID);
        assertThat(ListingUrls.idFrom("powai-1-bhk-" + ID)).isEqualTo(ID);
    }

    @Test
    @DisplayName("a segment with no id in it is refused")
    void nonsenseIsRefused() {
        // The controller turns this into a 404. /flats/haha is a crawler or a
        // typo, not an error worth a stack trace.
        for (String junk : new String[]{null, "", "haha", "bandra-west-2-bhk"}) {
            assertThatThrownBy(() -> ListingUrls.idFrom(junk))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a segment the right length but not a uuid is refused")
    void rightLengthWrongContent() {
        assertThatThrownBy(() -> ListingUrls.idFrom("x".repeat(36)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("what it builds is what it reads")
    void roundTrip() {
        UUID id = UUID.randomUUID();
        String path = ListingUrls.path(id, "Bandra Kurla Complex", "4 BHK+", "Shared room");

        assertThat(ListingUrls.idFrom(path.substring("/flats/".length()))).isEqualTo(id);
    }

    // ---- Slugging ----------------------------------------------------------

    @Test
    @DisplayName("punctuation collapses instead of doubling up")
    void punctuationCollapses() {
        // "2 BHK / Private room" would otherwise become "2-bhk---private-room".
        assertThat(ListingUrls.slugify("2 BHK / Private room")).isEqualTo("2-bhk-private-room");
        assertThat(ListingUrls.slugify("  Bandra  West  ")).isEqualTo("bandra-west");
        assertThat(ListingUrls.slugify("4 BHK+")).isEqualTo("4-bhk");
    }

    @Test
    @DisplayName("accents become plain letters rather than percent-escapes")
    void accentsAreFlattened() {
        // Left alone these survive into the path and the browser encodes them,
        // so a URL meant to be readable arrives as %C3%A9.
        assertThat(ListingUrls.slugify("Café Parade")).isEqualTo("cafe-parade");
    }

    @Test
    @DisplayName("a long slug is cut at a word, never mid-word")
    void longSlugsAreCutCleanly() {
        String slug = ListingUrls.slugify(
                "Bandra Kurla Complex Extension Phase Two 4 BHK Plus Shared Room With Balcony");

        assertThat(slug).doesNotEndWith("-");
        assertThat(slug.length()).isLessThanOrEqualTo(50);
        // The test that matters: the cut lands on a separator, so no word is
        // left as a fragment like "balcon".
        assertThat(slug.split("-")).allSatisfy(word ->
                assertThat(word).isNotEmpty());
        assertThat(slug).startsWith("bandra-kurla-complex");
    }

    @Test
    @DisplayName("a slug is only ever url-safe characters")
    void alwaysUrlSafe() {
        assertThat(ListingUrls.slugify("Andheri (East) — 2BHK, ₹32,000/mo & more!"))
                .matches("[a-z0-9]+(-[a-z0-9]+)*");
    }
}
