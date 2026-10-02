package com.nexttenant.listing;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Decides whether a listing that is no longer public is <em>gone</em> or merely
 * not here.
 *
 * <h2>Why the distinction is worth code</h2>
 *
 * <p>404 and 410 mean different things to a crawler. 404 is "not found", which
 * Google treats as possibly temporary: it keeps the URL, retries it for weeks,
 * and the page lingers in the index while it decides. 410 is "gone,
 * deliberately, stop asking", and Google drops the URL much faster.
 *
 * <p>Both are right at different moments in a listing's life, which is the
 * whole reason this is not a constant:
 *
 * <ul>
 *   <li>A room PAUSED this morning, or one whose owner is about to extend an
 *       expiry, is coming back. 410 would throw away an indexed page that is
 *       about to be live again, and getting it re-indexed takes far longer
 *       than the pause lasted.</li>
 *   <li>A room that expired three months ago is not coming back. Leaving it as
 *       a 404 means Google re-requests a dead URL for weeks and shows it in
 *       results in the meantime — somebody clicks a room in Powai and lands on
 *       an error page, which is a worse first impression than never ranking
 *       at all.</li>
 * </ul>
 *
 * <p>So the answer is a function of how long it has been gone.
 */
@Service
public class GoneListings {

    /**
     * How long a listing stays "not found" before it becomes "gone".
     *
     * <p>Long enough to cover the ordinary reasons a room comes back: a pause
     * over a festival, an expiry the leaver extends a fortnight late, a
     * take-down reversed after a report is reviewed. Past this, the room has
     * been relet or the plan changed, and the URL is honestly dead.
     */
    private static final Duration GRACE = Duration.ofDays(60);

    private final JdbcClient jdbc;

    public GoneListings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The status to return for a listing that {@code findPublicListing} refused.
     *
     * <p>Always 404 for a listing that does not exist, is a draft, or is
     * recently gone — and deliberately the same 404 for all three. A distinct
     * response for "exists but is hidden" would let anybody confirm a listing's
     * status after it stopped being public, which is exactly what pausing it
     * was for.
     */
    @Transactional(readOnly = true)
    public HttpStatus statusFor(UUID id) {
        // The dates that mean "this ended", with updated_at only as a fallback
        // for a row that carries neither.
        //
        // updated_at deliberately does NOT participate in the greatest(): a
        // trigger sets it on every write, so including it means any touch of
        // the row — a moderation note, a backfill, a status correction — resets
        // the clock and the listing is never gone. That bug is invisible,
        // because the symptom is a 404 that was supposed to be a 410.
        return jdbc.sql("""
                        SELECT status,
                               coalesce(
                                   greatest(expires_at, rented_at),
                                   expires_at,
                                   rented_at,
                                   updated_at
                               ) AS ended_at
                          FROM listings
                         WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, n) -> {
                    ListingStatus status = ListingStatus.valueOf(rs.getString("status"));
                    Instant endedAt = rs.getTimestamp("ended_at").toInstant();
                    return isGone(status, endedAt) ? HttpStatus.GONE : HttpStatus.NOT_FOUND;
                })
                .optional()
                // No row at all. A URL that never existed is not "gone" — 410
                // would be a claim about a page we never published, and a
                // crawler is entitled to retry a 404 in case we are mistaken.
                .orElse(HttpStatus.NOT_FOUND);
    }

    private static boolean isGone(ListingStatus status, Instant endedAt) {
        // A DRAFT was never published, so there is nothing to have gone. A
        // PAUSED listing is explicitly coming back. Only the three terminal
        // states qualify, and only after the grace period.
        boolean terminal = status == ListingStatus.EXPIRED
                || status == ListingStatus.RENTED
                || status == ListingStatus.TAKEN_DOWN;

        return terminal && endedAt.isBefore(Instant.now().minus(GRACE));
    }
}
