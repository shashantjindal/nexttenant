package com.nexttenant.moderation;

import com.nexttenant.listing.Listing;
import com.nexttenant.listing.ListingRepository;
import com.nexttenant.listing.ListingStatus;
import com.nexttenant.user.User;
import com.nexttenant.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reporting listings, reviewing reports, and removing accounts.
 *
 * <p>The design states the rule plainly: <em>"One click to report a listing.
 * Two confirmed reports and the account is gone."</em> Two safeguards keep that
 * from being a weapon:
 *
 * <ul>
 *   <li>{@code reports_uq} means one report per person per listing, so two
 *       reports are necessarily two different people;</li>
 *   <li>only a CONFIRMED report counts, so a moderator has agreed before
 *       anyone is removed.</li>
 * </ul>
 *
 * <p>Every moderator decision is written to {@code moderation_actions}. This is
 * the one place where one person acts on another's account, so "who did this,
 * and when?" must always be answerable.
 */
@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    /** "Two confirmed reports and the account is gone." */
    public static final int STRIKES_TO_REMOVE = 2;

    private final ReportRepository reports;
    private final ListingRepository listings;
    private final UserRepository users;
    private final SessionRevoker sessions;
    private final JdbcClient jdbc;
    private final Clock clock;

    public ModerationService(ReportRepository reports, ListingRepository listings,
                             UserRepository users, SessionRevoker sessions,
                             JdbcClient jdbc, Clock clock) {
        this.reports = reports;
        this.listings = listings;
        this.users = users;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ---- Reporting --------------------------------------------------------

    /**
     * Files a report.
     *
     * @throws ReportNotAllowedException with a message safe to show the person
     */
    @Transactional
    public Report report(UUID listingId, UUID reporterId, ReportReason reason, String detail) {
        Listing listing = listings.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (listing.isOwnedBy(reporterId)) {
            throw new ReportNotAllowedException("This is your own listing");
        }

        if (reports.findByListingIdAndReporterUserId(listingId, reporterId).isPresent()) {
            // Silently succeeding would be friendlier and would also let
            // somebody believe they had reported twice.
            throw new ReportNotAllowedException("You have already reported this listing");
        }

        try {
            Report report = reports.saveAndFlush(
                    new Report(listingId, reporterId, reason, trim(detail)));
            log.info("Report {} filed against listing {}", report.getId(), listingId);
            return report;
        } catch (DataIntegrityViolationException e) {
            throw new ReportNotAllowedException("You have already reported this listing");
        }
    }

    // ---- Review -----------------------------------------------------------

    /**
     * Confirms a report, and removes the account if this was the second strike.
     *
     * @return true if the account was removed as a result
     */
    @Transactional
    public boolean confirm(UUID reportId, UUID moderatorId, String note) {
        Report report = reports.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        report.confirm(clock.instant(), moderatorId);
        reports.saveAndFlush(report);
        audit(moderatorId, "REPORT_CONFIRMED", "REPORT", reportId, note);

        Listing listing = listings.findById(report.getListingId()).orElse(null);
        if (listing == null) {
            return false;
        }

        // A confirmed report always takes the listing down, whether or not it
        // is the second strike. Leaving a listing live that a moderator has
        // just agreed is a broker would be absurd.
        if (listing.getStatus() != ListingStatus.TAKEN_DOWN) {
            listing.takeDown(clock.instant());
            listings.saveAndFlush(listing);
            audit(moderatorId, "LISTING_TAKEN_DOWN", "LISTING", listing.getId(), note);
        }

        UUID ownerId = listing.getLeaverUserId();
        long strikes = reports.countConfirmedAgainstUser(ownerId);

        if (strikes >= STRIKES_TO_REMOVE) {
            removeAccount(ownerId, moderatorId,
                    "%d confirmed reports".formatted(strikes));
            return true;
        }

        log.info("User {} now has {} confirmed report(s)", ownerId, strikes);
        return false;
    }

    @Transactional
    public void dismiss(UUID reportId, UUID moderatorId, String note) {
        Report report = reports.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        report.dismiss(clock.instant(), moderatorId);
        reports.saveAndFlush(report);
        audit(moderatorId, "REPORT_DISMISSED", "REPORT", reportId, note);
    }

    // ---- Account actions --------------------------------------------------

    /**
     * Removes an account.
     *
     * <p>Three things happen together, and all three matter:
     *
     * <ol>
     *   <li>the account status becomes REMOVED, so it cannot sign in again;</li>
     *   <li>every listing they own is taken down, so nothing of theirs stays
     *       publicly visible;</li>
     *   <li>every session they hold is deleted, so access ends on their next
     *       request rather than whenever their session would have expired.</li>
     * </ol>
     *
     * <p>The third is only possible because sessions live in PostgreSQL. It is
     * the concrete reason this application does not use JWTs.
     */
    @Transactional
    public void removeAccount(UUID userId, UUID moderatorId, String reason) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        user.remove(clock.instant(), reason);
        users.saveAndFlush(user);

        List<Listing> theirs = listings.findByLeaverUserIdOrderByCreatedAtDesc(userId);
        theirs.stream()
                .filter(l -> l.getStatus() != ListingStatus.TAKEN_DOWN)
                .forEach(l -> l.takeDown(clock.instant()));
        listings.saveAll(theirs);
        listings.flush();

        int endedSessions = sessions.revokeAllFor(userId);

        audit(moderatorId, "USER_REMOVED", "USER", userId, reason);
        log.warn("Account {} removed ({}); {} listings taken down, {} sessions ended",
                userId, reason, theirs.size(), endedSessions);
    }

    @Transactional
    public void suspend(UUID userId, UUID moderatorId, String reason) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        user.suspend();
        users.saveAndFlush(user);

        // Suspension ends sessions too. A suspended account that could keep
        // using an open session would be suspended in name only.
        sessions.revokeAllFor(userId);
        audit(moderatorId, "USER_SUSPENDED", "USER", userId, reason);
    }

    @Transactional
    public void reinstate(UUID userId, UUID moderatorId, String note) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // removedAt and removedReason survive: the removal happened, and a
        // moderator reviewing a later report should be able to see it did.
        user.reinstate();
        users.saveAndFlush(user);
        audit(moderatorId, "USER_REINSTATED", "USER", userId, note);
    }

    // ---- Queue ------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Report> openQueue() {
        return reports.findByStatusOrderByCreatedAtAsc(ReportStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public long openCount() {
        return reports.countByStatus(ReportStatus.OPEN);
    }

    @Transactional(readOnly = true)
    public long strikesAgainst(UUID userId) {
        return reports.countConfirmedAgainstUser(userId);
    }

    // ---- Audit ------------------------------------------------------------

    /**
     * Appends to the moderation log.
     *
     * <p>Written with plain SQL rather than through an entity because this
     * table is append-only: it is never read back into an object, never
     * updated, and never deleted. An entity would imply all three are possible.
     */
    private void audit(UUID actorId, String action, String targetType, UUID targetId, String note) {
        jdbc.sql("""
                        INSERT INTO moderation_actions (actor_id, action, target_type, target_id, note)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .param(actorId)
                .param(action)
                .param(targetType)
                .param(targetId)
                .param(trim(note))
                .update();
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The report was refused for a reason worth showing the person. */
    public static class ReportNotAllowedException extends RuntimeException {
        public ReportNotAllowedException(String message) {
            super(message);
        }
    }
}
