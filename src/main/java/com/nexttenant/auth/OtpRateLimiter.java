package com.nexttenant.auth;

import com.nexttenant.common.Hashing;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Fixed-window rate limiting, counted in the database.
 *
 * <p>Deliberately NOT a JPA entity. Counting through an entity means
 * read-modify-write:
 *
 * <pre>
 *   var limit = repo.find(...);   // both requests read 4
 *   limit.increment();            // both compute 5
 *   repo.save(limit);             // both write 5 — six messages sent
 * </pre>
 *
 * <p>That is a lost update, and a rate limiter is precisely where an attacker
 * creates concurrency. Postgres can increment and report the new value in a
 * single atomic statement, so there is no window between reading and writing.
 *
 * <p>In the database rather than in memory because the limit must survive a
 * restart and hold across instances. An in-memory counter resets on every
 * deploy — which is when someone probing is most likely to be watching.
 */
@Component
public class OtpRateLimiter {

    private final JdbcClient jdbc;

    public OtpRateLimiter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records one use and returns the count within the current window.
     *
     * <p>INSERT ... ON CONFLICT DO UPDATE is Postgres's atomic upsert. The row
     * is created or incremented in one statement, under a row lock held by the
     * database, and RETURNING hands back the value that was actually stored —
     * not one this code guessed.
     *
     * @return the number of uses in this window, including this one
     */
    public int recordAndCount(RateLimitScope scope, String key, Instant now, Duration window) {
        String keyHash = Hashing.sha256Hex(key);
        Instant windowStart = floorToWindow(now, window);

        return jdbc.sql("""
                        INSERT INTO otp_rate_limits (scope, key_hash, window_start, attempt_count)
                        VALUES (?, ?, ?, 1)
                        ON CONFLICT (scope, key_hash, window_start)
                        DO UPDATE SET attempt_count = otp_rate_limits.attempt_count + 1
                        RETURNING attempt_count
                        """)
                .param(scope.name())
                .param(keyHash)
                .param(utc(windowStart))
                .query(Integer.class)
                .single();
    }

    /** Reads the current count without recording a use. */
    public int currentCount(RateLimitScope scope, String key, Instant now, Duration window) {
        return jdbc.sql("""
                        SELECT COALESCE(attempt_count, 0) FROM otp_rate_limits
                        WHERE scope = ? AND key_hash = ? AND window_start = ?
                        """)
                .param(scope.name())
                .param(Hashing.sha256Hex(key))
                .param(utc(floorToWindow(now, window)))
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    /** How long until the current window rolls over. */
    public Duration timeUntilReset(Instant now, Duration window) {
        return Duration.between(now, floorToWindow(now, window).plus(window));
    }

    /** Housekeeping: windows that closed long ago are of no further use. */
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM otp_rate_limits WHERE window_start < ?")
                .param(utc(cutoff))
                .update();
    }

    /**
     * Rounds an instant down to the start of its window, so every request in
     * the same hour shares one row.
     *
     * <p>The known cost of fixed windows is burst at a boundary: a caller can
     * spend a full quota at 10:59 and another at 11:00. A sliding window avoids
     * that at the price of storing every event. For sending SMS — where the
     * limit is about cost and abuse, not precision — the simpler structure is
     * the right trade.
     */
    /**
     * The Postgres JDBC driver cannot infer a SQL type for java.time.Instant:
     * an Instant is a point on the timeline with no zone attached, and the
     * driver will not guess one. Hibernate manages because it knows the mapped
     * column type; raw JDBC has no such context.
     *
     * <p>OffsetDateTime carries its offset, so the driver maps it to timestamptz
     * unambiguously.
     */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private Instant floorToWindow(Instant now, Duration window) {
        long windowSeconds = window.getSeconds();
        long epochSeconds = now.getEpochSecond();
        return Instant.ofEpochSecond(epochSeconds - (epochSeconds % windowSeconds))
                .truncatedTo(ChronoUnit.SECONDS);
    }
}
