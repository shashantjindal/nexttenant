-- ============================================================================
-- V5 — One-time passcodes
--
-- Two tables: the challenges themselves, and the rate-limit counters that stop
-- the SMS bill becoming the attack surface.
--
-- Note what is NOT stored here: the phone number. A challenge records only the
-- SHA-256 of the number it was issued for, which is enough for rate limiting
-- and for matching a verification to its challenge. The plaintext lives in the
-- user's HTTP session for the duration of the flow, so the database never holds
-- a phone number for an unauthenticated person.
-- ============================================================================


CREATE TABLE otp_challenges (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- SHA-256 of the E.164 number. Identifies the challenge without recording
    -- whose it is.
    phone_hash    VARCHAR(64) NOT NULL,

    -- HMAC-SHA256 of the code under a server-held secret — NOT a plain digest.
    --
    -- A 6-digit code has only a million possible values, so a leaked SHA-256
    -- of one is brute-forced in milliseconds: hashing alone buys nothing when
    -- the input space is enumerable. Without the server secret those million
    -- candidate digests cannot be computed at all.
    code_hmac     VARCHAR(64) NOT NULL,

    purpose       VARCHAR(20) NOT NULL DEFAULT 'LOGIN'
        CONSTRAINT otp_challenges_purpose_ck
        CHECK (purpose IN ('LOGIN', 'PHONE_CHANGE')),

    -- The real defence. Storage hardly matters if an attacker can grind codes
    -- against the live endpoint; six digits is a million guesses, which is
    -- under three hours at 100 requests per second. The cap is what makes a
    -- short, human-typeable code safe.
    attempts      SMALLINT    NOT NULL DEFAULT 0
        CONSTRAINT otp_challenges_attempts_ck CHECK (attempts >= 0),
    max_attempts  SMALLINT    NOT NULL DEFAULT 5
        CONSTRAINT otp_challenges_max_attempts_ck CHECK (max_attempts BETWEEN 1 AND 10),

    -- How many times this challenge has been (re)sent, and when last, so the
    -- resend cooldown in the design is enforced server-side rather than by a
    -- countdown in the browser that anyone can skip.
    send_count    SMALLINT    NOT NULL DEFAULT 1
        CONSTRAINT otp_challenges_send_count_ck CHECK (send_count BETWEEN 1 AND 10),
    last_sent_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,

    -- Set the moment a code is accepted. A challenge is single-use: without
    -- this, one intercepted code could be replayed indefinitely until expiry.
    consumed_at   TIMESTAMPTZ,

    CONSTRAINT otp_challenges_expiry_ck CHECK (expires_at > created_at)
);

-- Verification looks up the newest unconsumed challenge for a number.
CREATE INDEX otp_challenges_lookup_ix
    ON otp_challenges (phone_hash, created_at DESC)
    WHERE consumed_at IS NULL;

-- The cleanup sweep. Expired challenges are deleted rather than kept: they
-- have no analytical value and every retained row is one more thing that can
-- leak.
CREATE INDEX otp_challenges_expiry_ix ON otp_challenges (expires_at);

COMMENT ON TABLE  otp_challenges IS 'Ephemeral. Purged after expiry; never stores a phone number, only its hash.';
COMMENT ON COLUMN otp_challenges.code_hmac IS 'HMAC-SHA256 under a server secret. A plain digest of a 6-digit code is brute-forceable in milliseconds.';


-- ---------------------------------------------------------------------------
-- Rate limits
--
-- Fixed-window counters: one row per (scope, key, window). Simpler than a
-- sliding window and good enough here. The known weakness is burst at a window
-- boundary — a caller can spend a full quota at 10:59 and another at 11:00 —
-- which matters for a 10,000 req/s API and does not for sending an SMS.
--
-- Counted in the database rather than in memory because the limit must hold
-- across restarts and across instances. An in-memory limiter resets every
-- deploy, which is exactly when someone is most likely to be probing.
-- ---------------------------------------------------------------------------
CREATE TABLE otp_rate_limits (
    scope        VARCHAR(20) NOT NULL
        CONSTRAINT otp_rate_limits_scope_ck
        CHECK (scope IN ('PHONE', 'IP')),

    -- Hashed, not raw. The limiter has no business holding phone numbers or
    -- IP addresses in the clear, and a hash counts just as well.
    key_hash     VARCHAR(64) NOT NULL,

    window_start TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER    NOT NULL DEFAULT 1
        CONSTRAINT otp_rate_limits_count_ck CHECK (attempt_count >= 0),

    CONSTRAINT otp_rate_limits_pk PRIMARY KEY (scope, key_hash, window_start)
);

CREATE INDEX otp_rate_limits_window_ix ON otp_rate_limits (window_start);

COMMENT ON TABLE otp_rate_limits IS 'Fixed-window counters, hashed keys. In the database so limits survive restarts and span instances.';
