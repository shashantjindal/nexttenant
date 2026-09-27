package com.nexttenant.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RupeesTest {

    @Test
    @DisplayName("groups digits the Indian way, not the Western way")
    void usesIndianDigitGrouping() {
        // The last three digits group together, then twos. Getting this wrong
        // makes prices read as subtly foreign to the only audience this
        // product has.
        assertThat(Rupees.format(1_000)).isEqualTo("₹1,000");
        assertThat(Rupees.format(15_000)).isEqualTo("₹15,000");
        assertThat(Rupees.format(100_000)).isEqualTo("₹1,00,000");     // not 100,000
        assertThat(Rupees.format(1_178_00)).isEqualTo("₹1,17,800");    // not 117,800
        assertThat(Rupees.format(10_000_000)).isEqualTo("₹1,00,00,000"); // one crore
    }

    @Test
    @DisplayName("null renders as empty, so a draft never shows the word null")
    void nullIsEmpty() {
        assertThat(Rupees.format(null)).isEmpty();
        assertThat(Rupees.compact(null)).isEmpty();
    }

    @Test
    @DisplayName("compact form uses lakh and crore, as the prototypes do")
    void compactUsesLakhAndCrore() {
        assertThat(Rupees.compact(32_000)).isEqualTo("₹32,000");
        assertThat(Rupees.compact(4_800_000)).isEqualTo("₹48 lakh");
        assertThat(Rupees.compact(150_000)).isEqualTo("₹1.5 lakh");
        assertThat(Rupees.compact(12_000_000)).isEqualTo("₹1.2 crore");
    }

    @Test
    @DisplayName("whole values do not gain a trailing .0")
    void trimsTrailingZero() {
        assertThat(Rupees.compact(100_000)).isEqualTo("₹1 lakh");
        assertThat(Rupees.compact(10_000_000)).isEqualTo("₹1 crore");
    }
}
