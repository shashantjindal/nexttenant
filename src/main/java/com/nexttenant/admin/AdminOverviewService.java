package com.nexttenant.admin;

import com.nexttenant.moderation.ModerationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts for the admin landing page.
 *
 * <p>One statement with scalar subqueries rather than seven round trips. Not
 * for speed — at this size either would be instant — but because seven
 * separate counts can disagree with each other: a listing published between
 * the third query and the fifth appears in one total and not another, and the
 * page shows numbers that cannot all be true at once.
 *
 * <p>Raw SQL rather than repositories, deliberately. Every figure here is a
 * COUNT over one table, and routing that through JPA would load nothing and
 * cost a repository method per number. Reports stay behind
 * {@link ModerationService} because that count has a meaning beyond
 * arithmetic — "open" is a state the moderation code owns.
 */
@Service
public class AdminOverviewService {

    private final JdbcClient jdbc;
    private final ModerationService moderation;

    public AdminOverviewService(JdbcClient jdbc, ModerationService moderation) {
        this.jdbc = jdbc;
        this.moderation = moderation;
    }

    @Transactional(readOnly = true)
    public AdminOverview snapshot() {
        return jdbc.sql("""
                        SELECT
                          (SELECT count(*) FROM users)                        AS users,
                          (SELECT count(*) FROM users
                            WHERE created_at > now() - interval '7 days')     AS users_this_week,
                          (SELECT count(*) FROM listings
                            WHERE status = 'LIVE')                            AS live_listings,
                          (SELECT count(*) FROM listings
                            WHERE status = 'DRAFT')                           AS draft_listings,
                          (SELECT count(*) FROM listings
                            WHERE status = 'RENTED')                          AS rented_listings,
                          (SELECT count(*) FROM interest_requests
                            WHERE status = 'PENDING')                         AS pending_requests,
                          (SELECT count(*) FROM interest_requests
                            WHERE status = 'CONNECTED')                       AS connections
                        """)
                .query((rs, n) -> new AdminOverview(
                        rs.getLong("users"),
                        rs.getLong("users_this_week"),
                        rs.getLong("live_listings"),
                        rs.getLong("draft_listings"),
                        rs.getLong("rented_listings"),
                        rs.getLong("pending_requests"),
                        rs.getLong("connections"),
                        moderation.openCount()))
                .single();
    }
}
