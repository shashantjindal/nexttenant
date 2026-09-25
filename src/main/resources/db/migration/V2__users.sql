-- ============================================================================
-- V2 — Users
--
-- Split across two tables on a single principle: PUBLIC data and PRIVATE data
-- do not share a table. Loading a user's profile must not put their phone
-- number in memory. Reaching it requires an explicit join, written by someone
-- who meant to write it.
--
-- Contrast with a single `users` table carrying a "never expose this" comment:
-- a comment is not a constraint.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- Shared helper: keep updated_at honest.
--
-- `updated_at TIMESTAMPTZ DEFAULT now()` only fires on INSERT. Without a
-- trigger the column silently lies from the first UPDATE onward.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- ---------------------------------------------------------------------------
-- users — the PUBLIC profile.
--
-- Everything here may legitimately appear on a listing page to a stranger.
-- If a column could not, it belongs in user_private instead.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- "Only your first name is ever shown to other people." — listing wizard.
    -- There is deliberately no surname column: we never collect one, so we can
    -- never leak one.
    first_name              VARCHAR(40),

    gender                  VARCHAR(20)
        CONSTRAINT users_gender_ck
        CHECK (gender IN ('MALE', 'FEMALE', 'OTHER', 'UNDISCLOSED')),

    -- Brackets, not a date of birth. A bracket is enough to power the
    -- "flatmate age range" filter and is far less identifying.
    age_bracket             VARCHAR(20)
        CONSTRAINT users_age_bracket_ck
        CHECK (age_bracket IN ('AGE_18_22', 'AGE_23_27', 'AGE_28_32',
                               'AGE_33_40', 'AGE_41_PLUS')),

    -- SUSPENDED hides their listings pending review; REMOVED is the terminal
    -- state after two confirmed reports.
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT users_status_ck
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REMOVED')),

    -- Trust badges. Note carefully: the FACT of verification is public, the
    -- verified address itself is private and lives in user_private. The design
    -- shows "Work email verified" to strangers but the address only to the
    -- owner. Two different facts, two different audiences, two tables.
    phone_verified_at       TIMESTAMPTZ,
    work_email_verified_at  TIMESTAMPTZ,

    -- "Member since" on the leaver card.
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at            TIMESTAMPTZ
);

CREATE TRIGGER users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Moderation queues and admin search filter on status; ACTIVE is the vast
-- majority, so index only the rows actually worth finding.
CREATE INDEX users_status_ix ON users (status)
    WHERE status <> 'ACTIVE';


-- ---------------------------------------------------------------------------
-- user_private — everything that must never reach a public page.
--
-- Same primary key as `users` (one-to-one), so a join is cheap but never
-- implicit. ON DELETE CASCADE means deleting a user genuinely removes their
-- contact details rather than orphaning them.
-- ---------------------------------------------------------------------------
CREATE TABLE user_private (
    user_id      UUID        PRIMARY KEY
                             REFERENCES users (id) ON DELETE CASCADE,

    -- Stored in E.164 (+919876543210) so there is exactly one representation
    -- of a number. Without normalisation "9876543210", "+91 98765 43210" and
    -- "09876543210" are three accounts for one person, and "one number, one
    -- account" quietly stops being true.
    phone_e164   VARCHAR(16) NOT NULL,

    -- SHA-256 of the E.164 number. Lets OTP rate limiting and "does this
    -- number exist" checks run WITHOUT reading the number itself, so those
    -- code paths never handle the real value.
    phone_hash   CHAR(64)    NOT NULL,

    -- The verified address. The BADGE lives in users; this does not.
    work_email   VARCHAR(255),

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- "Every account is one OTP-verified Indian number. One number, one
    -- account." Enforced here rather than hoped for in the service layer.
    CONSTRAINT user_private_phone_uq      UNIQUE (phone_e164),
    CONSTRAINT user_private_phone_hash_uq UNIQUE (phone_hash),

    -- Indian mobile numbers are +91 followed by 10 digits starting 6-9.
    -- Garbage cannot be stored even by a bug or a direct psql session.
    CONSTRAINT user_private_phone_format_ck
        CHECK (phone_e164 ~ '^\+91[6-9][0-9]{9}$'),

    -- Cheap sanity check; real validation happens at the verification step.
    CONSTRAINT user_private_work_email_ck
        CHECK (work_email IS NULL OR work_email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$')
);

CREATE TRIGGER user_private_set_updated_at
    BEFORE UPDATE ON user_private
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


COMMENT ON TABLE  users        IS 'Public profile. Safe to render to strangers.';
COMMENT ON TABLE  user_private IS 'Contact details. Never rendered publicly; reaching this requires a deliberate join.';
COMMENT ON COLUMN users.work_email_verified_at IS 'Public trust badge. The address itself is in user_private.';
