package com.nexttenant.admin;

import com.nexttenant.auth.PhoneNumbers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only listings of accounts and rooms, for an operator looking into
 * something.
 *
 * <p><b>This is the one place private data is shown in bulk, and it is a
 * deliberate exception.</b> Everything else in this application goes to some
 * length to keep contact details apart from public data — the table split, the
 * DTOs without phone fields, {@code ContactRelease} as a single choke point.
 * An admin investigating a report genuinely needs to identify an account, so
 * the exception exists; it is drawn as narrowly as still being useful allows.
 *
 * <p><b>Email is shown in full, the phone number is masked.</b> The address is
 * the identity — it is how a person is recognised, searched for and matched to
 * a report, and masking it would make the page pointless. A phone number
 * identifies nothing an operator is looking for; it is only useful for
 * contacting somebody, which is not what this page is for. So the list shows
 * enough to recognise a number and not enough to dial it. The full value is
 * still in the database for the rare case that genuinely needs it, which is a
 * deliberate act rather than something on screen by default.
 *
 * <p>If this account is ever compromised, that distinction is the difference
 * between leaking who has an account and leaking every phone number on the
 * site.
 */
@Service
public class AdminDirectory {

    /** Page size. Enough to scan, small enough that nothing is dumped at once. */
    public static final int PAGE = 50;

    private final JdbcClient jdbc;

    public AdminDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param maskedPhone e.g. "+91 ***** 43210", or null when none is recorded
     */
    public record AccountRow(
            UUID id,
            String firstName,
            String email,
            String maskedPhone,
            String status,
            boolean admin,
            boolean phoneVerified,
            boolean workEmailVerified,
            long listings,
            Instant joined
    ) {
        /**
         * Formatted here rather than in the template.
         *
         * <p>Thymeleaf cannot format an Instant without being told a zone, and
         * a zone chosen in a template is a decision nobody will find again.
         * IST, because whoever reads this page is in Mumbai.
         */
        public String joinedOn() {
            return DATE.format(joined);
        }
    }

    private static final java.time.format.DateTimeFormatter DATE =
            java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
                    .withZone(java.time.ZoneId.of("Asia/Kolkata"));

    public record ListingRow(
            UUID id,
            String area,
            String status,
            Integer rent,
            String ownerName,
            String ownerEmail,
            long requests,
            Instant createdAt
    ) {
        public String createdOn() {
            return DATE.format(createdAt);
        }
    }

    /**
     * Accounts, newest first, optionally narrowed by a search term.
     *
     * <p>The search matches on email and first name. It does NOT match on
     * phone number — a box that turns a phone number into an account would be
     * a lookup service for anybody who reached this page, and an operator
     * arriving from a report already has a listing or an account to work from.
     *
     * <p>Note the {@code CAST(:term AS text)}, which is not decoration.
     * Postgres infers a parameter's type from how it is used, and a parameter
     * whose only use is {@code IS NULL} gives it nothing to work with: it
     * refuses the statement with "could not determine data type of parameter
     * $1". The cast is what makes the no-search case — by far the common one —
     * work at all.
     */
    @Transactional(readOnly = true)
    public List<AccountRow> accounts(String search, int page) {
        String term = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase() + "%";

        return jdbc.sql("""
                        SELECT u.id, u.first_name, u.status, u.is_admin,
                               u.phone_verified_at, u.work_email_verified_at, u.created_at,
                               up.email, up.phone_e164,
                               (SELECT count(*) FROM listings l
                                 WHERE l.leaver_user_id = u.id) AS listing_count
                          FROM users u
                          JOIN user_private up ON up.user_id = u.id
                         WHERE (CAST(:term AS text) IS NULL
                                OR lower(up.email) LIKE :term
                                OR lower(coalesce(u.first_name, '')) LIKE :term)
                         ORDER BY u.created_at DESC
                         LIMIT :limit OFFSET :offset
                        """)
                .param("term", term)
                .param("limit", PAGE)
                .param("offset", (long) page * PAGE)
                .query((rs, n) -> new AccountRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("first_name"),
                        rs.getString("email"),
                        // Masked here rather than in the template, so there is
                        // no path by which the full number reaches the HTML.
                        maskOrNull(rs.getString("phone_e164")),
                        rs.getString("status"),
                        rs.getBoolean("is_admin"),
                        rs.getTimestamp("phone_verified_at") != null,
                        rs.getTimestamp("work_email_verified_at") != null,
                        rs.getLong("listing_count"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /**
     * Null when there is no number, a mask when there is.
     *
     * <p>{@link PhoneNumbers#mask} returns an empty string for anything it
     * cannot mask, which is right for the OTP screen and wrong here: a column
     * containing {@code ""} renders as a blank cell that reads like a bug
     * rather than "this account signed up by email". Null lets the template
     * say so with an em dash.
     *
     * <p>Since V14, phone is optional on an account, so this is the normal
     * case for anybody who joined after email login.
     */
    private static String maskOrNull(String e164) {
        String masked = PhoneNumbers.mask(e164);
        return masked.isEmpty() ? null : masked;
    }

    /** Listings, newest first, optionally narrowed to one status. */
    @Transactional(readOnly = true)
    public List<ListingRow> listings(String status, int page) {
        String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase();

        return jdbc.sql("""
                        SELECT l.id, l.area_name, l.status, l.monthly_rent_inr, l.created_at,
                               u.first_name AS owner_name, up.email AS owner_email,
                               (SELECT count(*) FROM interest_requests r
                                 WHERE r.listing_id = l.id) AS request_count
                          FROM listings l
                          JOIN users u ON u.id = l.leaver_user_id
                          JOIN user_private up ON up.user_id = u.id
                         WHERE (CAST(:wanted AS text) IS NULL OR l.status = :wanted)
                         ORDER BY l.created_at DESC
                         LIMIT :limit OFFSET :offset
                        """)
                .param("wanted", wanted)
                .param("limit", PAGE)
                .param("offset", (long) page * PAGE)
                .query((rs, n) -> new ListingRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("area_name"),
                        rs.getString("status"),
                        (Integer) rs.getObject("monthly_rent_inr"),
                        rs.getString("owner_name"),
                        rs.getString("owner_email"),
                        rs.getLong("request_count"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
