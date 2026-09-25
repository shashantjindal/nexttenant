-- ============================================================================
-- V9 — Moderation
--
-- Adds the admin flag and the bookkeeping the two-strikes rule needs.
--
-- The design states the rule plainly: "One click to report a listing. Two
-- confirmed reports and the account is gone." Two things make that safe rather
-- than a weapon:
--
--   * reports_uq (from V4) means one report per person per listing, so "two
--     reports" necessarily means two different people;
--   * a report only counts once a human CONFIRMS it, so two coordinated
--     accounts cannot remove someone without a moderator agreeing.
-- ============================================================================

-- Admin rights. A boolean rather than a roles table: there is exactly one
-- privileged role and no sign of a second. A roles table would be modelling a
-- requirement nobody has.
ALTER TABLE users
    ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;

-- Finding the handful of admins should not scan every user.
CREATE INDEX users_admin_ix ON users (is_admin) WHERE is_admin;

-- When and why an account was removed. Kept separate from `status` so the
-- reason survives a later reinstatement: "this account was removed in March
-- for asking for money" is a fact worth keeping even if the decision is undone.
ALTER TABLE users
    ADD COLUMN removed_at     TIMESTAMPTZ,
    ADD COLUMN removed_reason VARCHAR(200);

COMMENT ON COLUMN users.is_admin IS 'Moderator rights. Granted only by direct database access; there is no UI to promote a user.';
COMMENT ON COLUMN users.removed_reason IS 'Why the account was removed. Kept even if the account is later reinstated.';


-- ---------------------------------------------------------------------------
-- An audit trail for moderator actions.
--
-- Moderation is the one place where one person acts on another's account, so it
-- is the one place where "who did this, and when?" must always be answerable.
-- Without a log, a disputed removal is one person's word against another's.
-- ---------------------------------------------------------------------------
CREATE TABLE moderation_actions (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- ON DELETE SET NULL, not CASCADE: deleting a moderator's account must not
    -- erase the record of decisions they made.
    actor_id     UUID        REFERENCES users (id) ON DELETE SET NULL,

    action       VARCHAR(40) NOT NULL
        CONSTRAINT moderation_actions_action_ck
        CHECK (action IN ('REPORT_CONFIRMED', 'REPORT_DISMISSED',
                          'USER_SUSPENDED', 'USER_REMOVED', 'USER_REINSTATED',
                          'LISTING_TAKEN_DOWN')),

    target_type  VARCHAR(20) NOT NULL
        CONSTRAINT moderation_actions_target_ck
        CHECK (target_type IN ('USER', 'LISTING', 'REPORT')),
    target_id    UUID        NOT NULL,

    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX moderation_actions_target_ix ON moderation_actions (target_type, target_id, created_at DESC);
CREATE INDEX moderation_actions_actor_ix  ON moderation_actions (actor_id, created_at DESC);

COMMENT ON TABLE moderation_actions IS 'Append-only audit of moderator decisions. Never updated or deleted.';
