-- ============================================================================
-- V11 — Work email verification
--
-- The "Work email verified" badge has been on the listing page and the profile
-- strength meter since the beginning, with nothing behind it. A trust signal
-- nobody can earn is worse than no trust signal: it is a claim the product
-- makes that it cannot honour.
--
-- This mirrors V5's OTP tables closely and deliberately. Same HMAC discipline,
-- same attempt cap, same single-use rule, same resend counters. Somebody who
-- has read V5 can read this in a minute, and — more to the point — a security
-- fix made to one is obviously needed in the other.
--
-- WHAT IS NOT STORED: the email address. Only its SHA-256, which is enough to
-- match a verification to its challenge. The plaintext lives in the HTTP
-- session for the length of the flow and reaches user_private only once it has
-- been proved. Storing it earlier would mean the database holds an unverified
-- claim about somebody who may not even be the person typing.
-- ============================================================================

CREATE TABLE work_email_challenges (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Unlike an OTP challenge, this one belongs to somebody: verification only
    -- happens while signed in. ON DELETE CASCADE because a challenge outliving
    -- its account is pure liability.
    user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- SHA-256 of the lowercased address. Identifies the challenge without
    -- recording where the code was sent.
    email_hash    VARCHAR(64) NOT NULL,

    -- HMAC-SHA256 under the server secret, not a plain digest — six digits is
    -- a million candidates, and a million SHA-256s is milliseconds of work.
    -- See V5 for the full argument.
    code_hmac     VARCHAR(64) NOT NULL,

    attempts      SMALLINT    NOT NULL DEFAULT 0
        CONSTRAINT work_email_challenges_attempts_ck CHECK (attempts >= 0),
    max_attempts  SMALLINT    NOT NULL DEFAULT 5
        CONSTRAINT work_email_challenges_max_attempts_ck CHECK (max_attempts BETWEEN 1 AND 10),

    send_count    SMALLINT    NOT NULL DEFAULT 1
        CONSTRAINT work_email_challenges_send_count_ck CHECK (send_count BETWEEN 1 AND 10),
    last_sent_at  TIMESTAMPTZ NOT NULL,

    -- No DEFAULT now() on either of these, and the expiry check below is why.
    -- If created_at came from the database and expires_at from the injected
    -- Clock, the constraint would be comparing two different time sources and
    -- would fail whenever the app server and database clocks drifted. The
    -- application owns both. This exact bug cost us an afternoon on V5.
    created_at    TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,

    consumed_at   TIMESTAMPTZ,

    CONSTRAINT work_email_challenges_expiry_ck CHECK (expires_at > created_at)
);

-- Verification looks up the newest unconsumed challenge for a user.
CREATE INDEX work_email_challenges_lookup_ix
    ON work_email_challenges (user_id, created_at DESC)
    WHERE consumed_at IS NULL;

-- For the cleanup sweep.
CREATE INDEX work_email_challenges_expiry_ix ON work_email_challenges (expires_at);

COMMENT ON TABLE work_email_challenges IS
    'Ephemeral. Purged after expiry; never stores an email address, only its hash.';


-- ---------------------------------------------------------------------------
-- One work address, one account.
--
-- Without this, one person with access to one corporate mailbox can put the
-- "Work email verified" badge on as many accounts as they like — which is
-- precisely the abuse the badge exists to make expensive. A partial index so
-- the many rows with no work email do not collide with each other, and on
-- lower() because nobody considers Asha@ and asha@ to be two people.
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX user_private_work_email_uq
    ON user_private (lower(work_email))
    WHERE work_email IS NOT NULL;
