package com.househunting.common;

import java.util.Locale;

/**
 * Formats whole rupees for display.
 *
 * <p>Indian digit grouping is <strong>not</strong> the Western 3-3-3 pattern.
 * It groups the last three digits, then twos:
 *
 * <pre>
 *   Western:  1,000,000     10,000,000
 *   Indian:   10,00,000     1,00,00,000   (ten lakh, one crore)
 * </pre>
 *
 * <p>Getting this wrong makes prices read as subtly foreign to exactly the
 * audience the product is for. {@code Locale.of("en", "IN")} knows the rule,
 * so we never implement the grouping by hand.
 */
public final class Rupees {

    private Rupees() {
    }

    /**
     * @return e.g. {@code ₹32,000} or {@code ₹1,10,000}; empty string for null,
     *         so templates never print "null" when a draft has no rent yet
     */
    public static String format(Integer amount) {
        if (amount == null) {
            return "";
        }
        return "₹" + group(amount);
    }

    /**
     * Applies Indian digit grouping: the last three digits, then twos.
     *
     * <p>Written by hand for two reasons, both discovered by the test rather
     * than assumed:
     *
     * <ul>
     *   <li>{@code Locale.of("en","IN")} does not produce Indian grouping on
     *       this JDK — it gives "100,000" — and locale data varies by JDK
     *       version and locale provider.</li>
     *   <li>{@code java.text.DecimalFormat} supports only ONE grouping size.
     *       A pattern such as {@code #,##,##0} is accepted but the JDK uses
     *       only the rightmost interval, so it behaves exactly like
     *       {@code #,##0}. ICU's DecimalFormat supports secondary grouping;
     *       the JDK's does not.</li>
     * </ul>
     *
     * <p>Ten lines that are deterministic on every JVM beats a library call
     * that is right on some of them.
     */
    static String group(long value) {
        boolean negative = value < 0;
        String digits = Long.toString(Math.abs(value));

        if (digits.length() <= 3) {
            return negative ? "-" + digits : digits;
        }

        String lastThree = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);

        StringBuilder grouped = new StringBuilder();
        int i = rest.length();
        while (i > 2) {                                     // take twos from the right
            grouped.insert(0, "," + rest.substring(i - 2, i));
            i -= 2;
        }
        grouped.insert(0, rest.substring(0, i));            // the remainder on the left

        String result = grouped + "," + lastThree;
        return negative ? "-" + result : result;
    }

    /**
     * Compact form for cards and headlines, matching how the prototypes write
     * large figures: {@code ₹48 lakh}, {@code ₹1.2 crore}.
     */
    public static String compact(Integer amount) {
        return amount == null ? "" : compact(amount.longValue());
    }

    /**
     * The same, for figures that are SUMS rather than single prices.
     *
     * <p>A rent fits in an int comfortably; a total across every flat on the
     * site does not necessarily. Overflowing to a negative number on a
     * headline statistic is the kind of bug that only appears once the site is
     * doing well, which is the worst time to find it.
     */
    public static String compact(long amount) {
        if (amount >= 10_000_000) {
            double crore = amount / 10_000_000.0;
            return "₹" + trimZero(crore) + " crore";
        }
        if (amount >= 100_000) {
            double lakh = amount / 100_000.0;
            return "₹" + trimZero(lakh) + " lakh";
        }
        // Below a lakh it is small enough that an int is certain to hold it.
        return format((int) amount);
    }

    /** 1.0 -> "1", 1.5 -> "1.5". Avoids "₹1.0 lakh". */
    private static String trimZero(double value) {
        return value == Math.floor(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
