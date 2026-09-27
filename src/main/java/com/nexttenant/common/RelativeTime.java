package com.nexttenant.common;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;

/**
 * Human phrasing for timestamps: "2 days ago", "Member since March 2026".
 *
 * <p>Formatted in Java rather than in Thymeleaf. A template expression that
 * computes elapsed time cannot be unit tested, and gets copied into the next
 * template with a subtle difference.
 */
public final class RelativeTime {

    private RelativeTime() {
    }

    /** "Posted 2 days ago" on the listing detail page. */
    public static String ago(Instant then, Instant now) {
        if (then == null) {
            return "";
        }
        Duration elapsed = Duration.between(then, now);

        long minutes = elapsed.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return plural(minutes, "minute") + " ago";
        }

        long hours = elapsed.toHours();
        if (hours < 24) {
            return plural(hours, "hour") + " ago";
        }

        long days = elapsed.toDays();
        if (days < 7) {
            return plural(days, "day") + " ago";
        }
        if (days < 30) {
            return plural(days / 7, "week") + " ago";
        }
        if (days < 365) {
            return plural(days / 30, "month") + " ago";
        }
        return plural(days / 365, "year") + " ago";
    }

    /**
     * "March 2026" for "Member since".
     *
     * <p>Month and year only. A joining date to the day is more precision than
     * the trust signal needs, and precision about a person is worth giving away
     * only when it earns something.
     */
    public static String monthYear(Instant instant) {
        if (instant == null) {
            return "";
        }
        return instant.atZone(java.time.ZoneId.of("Asia/Kolkata"))
                .format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy",
                        java.util.Locale.ENGLISH));
    }

    /** "in 12 days" / "today" for availability. */
    public static String until(LocalDate date, LocalDate today) {
        if (date == null) {
            return "";
        }
        if (!date.isAfter(today)) {
            return "available now";
        }
        Period period = Period.between(today, date);
        int days = (int) today.until(date, java.time.temporal.ChronoUnit.DAYS);
        if (days < 31) {
            return "in " + plural(days, "day");
        }
        return "in " + plural(period.toTotalMonths(), "month");
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
