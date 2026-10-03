package com.nexttenant.listing.wizard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Reading what people actually type into the listing fields.
 *
 * <p>Pulled out of {@code WizardController} when a second screen started
 * posting the same fields. These are not formatting helpers — each one is a
 * rule about what a value means, and two copies of a rule is two rules.
 *
 * <p>The money one in particular has already been wrong once in a way that
 * cost real data: stripping non-digits turned "0234ax" into ₹234 and saved it,
 * on the field that decides what somebody is asking for, with nothing on
 * screen to say it had happened. A second copy of that rule is a second chance
 * to get it wrong, on the door nobody tests as often.
 */
public final class ListingInputs {

    private ListingInputs() {
    }

    /**
     * "Today" means today in Mumbai.
     *
     * <p>The Clock bean is {@code systemUTC}, which is right for timestamps
     * and wrong for a calendar date: between midnight and half past five in
     * the morning IST it is still yesterday in UTC, so the date picker would
     * offer a day that has already gone for the person reading it. Everyone
     * using this is in one city; this is that city.
     */
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(IST));
    }

    /**
     * The earliest date the picker will offer.
     *
     * <p>Today, unless the listing already holds an earlier one — an August
     * date on a listing published in August still means "available now", and
     * its owner should not be blocked from saving an edit to something else
     * because of it.
     */
    public static LocalDate earliestMoveIn(LocalDate existing, LocalDate today) {
        return existing != null && existing.isBefore(today) ? existing : today;
    }

    /**
     * What counts as somebody typing a number: digits, and the separators the
     * site itself prints. A rupee sign because it is beside the field, commas
     * and spaces because "32,000" and "32 000" are how the amount is written
     * everywhere else on the page.
     */
    private static final Pattern MONEY = Pattern.compile("[\u20B9\s,0-9]+");

    /**
     * Accepts what people actually type into a money field.
     *
     * <p>"32,000", "₹32000" and "32000 " are all the same number. Rejecting
     * the first two would be technically defensible and would irritate
     * everybody, because the rest of the site renders rents with exactly those
     * separators.
     *
     * <p>Anything else is refused rather than filtered out. A typo is worth an
     * error message; it is not worth guessing at.
     */
    public static Integer rupees(String field, String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String cleaned = input.trim();
        if (!MONEY.matcher(cleaned).matches()) {
            throw new InvalidAmountException(field);
        }

        String digits = cleaned.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            // Longer than an int: eleven digits of rent is a typo too.
            throw new InvalidAmountException(field);
        }
    }

    public static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * The move-in date, refused if it is in the past.
     *
     * <p>"Available from 3 August" on a listing read in October is not a date,
     * it is a sign nobody has looked at the listing in months — and it is the
     * one field a searcher filters on, so a stale value quietly removes the
     * room from the results it should be in.
     *
     * <p>The exception is a date already stored. A listing published in August
     * for an August move-in is still perfectly valid in October; it simply
     * means "available now". Refusing to let an unrelated edit be saved,
     * because a field nobody touched is now in the past, would be the
     * validation making the listing worse.
     */
    public static LocalDate parseDate(String value, LocalDate current, LocalDate today) {
        if (value == null || value.isBlank()) {
            return null;
        }

        LocalDate parsed;
        try {
            parsed = LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidDateException("That move-in date could not be read.");
        }

        if (parsed.isBefore(today) && !parsed.equals(current)) {
            throw new InvalidDateException("The move-in date cannot be in the past.");
        }
        return parsed;
    }

    /** A money field that was not a number. Carries the label to point at. */
    public static class InvalidAmountException extends RuntimeException {
        public InvalidAmountException(String field) {
            super("%s needs to be a number — digits only.".formatted(field));
        }
    }

    /** A move-in date that was unreadable or already gone. */
    public static class InvalidDateException extends RuntimeException {
        public InvalidDateException(String message) {
            super(message);
        }
    }
}
