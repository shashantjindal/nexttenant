-- ============================================================================
-- V6 — Spring Session tables
--
-- Copied verbatim from spring-session-jdbc's own
-- org/springframework/session/jdbc/schema-postgresql.sql. Spring Session's
-- queries are written against exactly these names and types, so this is not a
-- schema to improve — note the CHAR(36) columns, which V2 deliberately avoided
-- for our own tables because CHAR space-pads. Here the library expects them.
--
-- Flyway owns this rather than letting Spring Session create it at startup
-- (spring.session.jdbc.initialize-schema=never), so the schema has one owner
-- and one ordered history.
--
-- Why sessions live in PostgreSQL rather than in Tomcat's memory:
--
--   * they survive a restart, so a deploy does not log everybody out
--   * every instance sees them, so scaling out does not require sticky sessions
--   * a session can be DELETED — which is what makes "two confirmed reports and
--     the account is gone" take effect immediately. A JWT would stay valid
--     until it expired, since nothing server-side can revoke it.
-- ============================================================================

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT          NOT NULL,
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
-- Indexed so every session belonging to one user can be found and deleted —
-- the mechanism behind suspending an account.
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);

COMMENT ON TABLE SPRING_SESSION IS 'Owned by spring-session-jdbc. Schema copied verbatim from the library; do not modify.';
