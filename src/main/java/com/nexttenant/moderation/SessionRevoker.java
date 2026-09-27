package com.nexttenant.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Ends every session belonging to one account.
 *
 * <p>This is the payoff of the decision made back in phase 5. Sessions live in
 * PostgreSQL, so removing an account's access is a DELETE that takes effect on
 * the account's very next request. With a JWT there would be nothing to delete:
 * the token stays valid until it expires, and a removed broker would keep
 * working for the rest of its lifetime — unless we kept a revocation list,
 * which is server-side session state with extra steps.
 *
 * <p>It works because {@code AuthenticatedUser.getName()} returns the user id,
 * which Spring Session stores in PRINCIPAL_NAME. That column is indexed
 * (SPRING_SESSION_IX3), so this stays cheap.
 */
@Service
public class SessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);

    private final JdbcClient jdbc;

    public SessionRevoker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return how many sessions were ended */
    @Transactional
    public int revokeAllFor(UUID userId) {
        int deleted = jdbc.sql("DELETE FROM spring_session WHERE principal_name = ?")
                .param(userId.toString())
                .update();

        if (deleted > 0) {
            log.info("Revoked {} session(s) for user {}", deleted, userId);
        }
        return deleted;
    }

    @Transactional(readOnly = true)
    public int activeSessionCount(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = ?")
                .param(userId.toString())
                .query(Integer.class)
                .single();
    }
}
