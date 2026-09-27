package com.nexttenant.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compact rupee format used on headline figures.
 *
 * <p>Gained a {@code long} overload when the landing page's "brokerage saved"
 * stopped being a hardcoded constant and became a SUM across every listing.
 */
class RupeesCompactTest {

    @Test
    @DisplayName("a sum too large for an int does not wrap to a negative")
    void largeSumsSurvive() {
        // 300 crore. An int holds about 214 crore, so the old signature would
        // have wrapped this to a negative number and printed a headline
        // statistic of "-₹1.9 crore". The kind of bug that only shows up once
        // the site is doing well, which is the worst moment to find it.
        assertThat(Rupees.compact(3_000_000_000L)).isEqualTo("₹300 crore");
    }

    @Test
    @DisplayName("zero reads as zero rather than as an empty string")
    void zeroIsShown() {
        // Launch day. A real ₹0 is the honest answer and must render as
        // something; an empty string would leave a blank box under a label.
        assertThat(Rupees.compact(0L)).isNotBlank();
        assertThat(Rupees.compact(0L)).contains("0");
    }

    @Test
    @DisplayName("the long and Integer forms agree")
    void bothFormsAgree() {
        // One delegates to the other, and this is what keeps that true.
        assertThat(Rupees.compact(4_800_000L)).isEqualTo(Rupees.compact(4_800_000));
        assertThat(Rupees.compact(32_000L)).isEqualTo(Rupees.compact(32_000));
    }

    @Test
    @DisplayName("lakh and crore boundaries read the way people write them")
    void boundariesReadNaturally() {
        assertThat(Rupees.compact(99_999L)).doesNotContain("lakh");
        assertThat(Rupees.compact(100_000L)).isEqualTo("₹1 lakh");
        assertThat(Rupees.compact(4_800_000L)).isEqualTo("₹48 lakh");
        assertThat(Rupees.compact(10_000_000L)).isEqualTo("₹1 crore");
        // Not "₹1.0 crore".
        assertThat(Rupees.compact(10_000_000L)).doesNotContain(".0");
    }
}
