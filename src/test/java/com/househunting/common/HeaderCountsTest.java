package com.househunting.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The header's badge numbers, and the grammar that goes with them.
 *
 * <p>No Spring context: this is a record with four small methods, and a test
 * that needs no context starts in milliseconds and therefore gets run.
 */
class HeaderCountsTest {

    @Test
    @DisplayName("a signed-out visitor has nothing, without a null anywhere")
    void noneIsEmptyRatherThanNull() {
        // NONE rather than null so no template has to null-check before asking.
        // A null here would surface as a Thymeleaf error on every page for
        // signed-out visitors, which is most of them.
        assertThat(HeaderCounts.NONE.wishlist()).isZero();
        assertThat(HeaderCounts.NONE.pendingRequests()).isZero();
        assertThat(HeaderCounts.NONE.hasWishlist()).isFalse();
        assertThat(HeaderCounts.NONE.hasPendingRequests()).isFalse();
    }

    @Test
    @DisplayName("the sentence reads correctly at one")
    void singularAgrees() {
        HeaderCounts one = new HeaderCounts(0, 1);

        // "1 people want your room" is the kind of detail that makes a site
        // feel unfinished, and it is only ever visible to the person who has
        // exactly one request — who is also the person you least want to lose.
        assertThat(one.pendingNoun()).isEqualTo("person");
        assertThat(one.pendingVerb()).isEqualTo("wants");
        assertThat(sentence(one)).isEqualTo("1 person wants your room");
    }

    @Test
    @DisplayName("and at anything else")
    void pluralAgrees() {
        assertThat(sentence(new HeaderCounts(0, 2))).isEqualTo("2 people want your room");
        assertThat(sentence(new HeaderCounts(0, 17))).isEqualTo("17 people want your room");

        // Zero takes the plural too. The banner is hidden at zero, so this is
        // about the rule being total rather than about a string anyone reads.
        assertThat(new HeaderCounts(0, 0).pendingNoun()).isEqualTo("people");
    }

    @Test
    @DisplayName("the two counts are independent")
    void countsDoNotInterfere() {
        // They come from different tables and mean different things; a bug
        // that crossed them would show a wishlist number on the requests badge
        // and look plausible.
        HeaderCounts counts = new HeaderCounts(5, 0);

        assertThat(counts.hasWishlist()).isTrue();
        assertThat(counts.hasPendingRequests()).isFalse();
    }

    /** Mirrors the literal substitution the landing template performs. */
    private static String sentence(HeaderCounts counts) {
        return "%d %s %s your room".formatted(
                counts.pendingRequests(), counts.pendingNoun(), counts.pendingVerb());
    }
}
