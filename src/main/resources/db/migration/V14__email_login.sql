-- ============================================================================
-- V14 — Email as the login identity; the mobile number as contact detail
--
-- WHY. Signing in used to prove ownership of a phone number, and that one act
-- did two unrelated jobs: it identified the returning person, and it proved the
-- number was reachable. India's DLT regime makes the second job expensive to
-- start (entity registration, header and template approval, weeks of it), so
-- the two jobs are separated here:
--
--   identity  -> email, verified by OTP, cheap and available today
--   contact   -> mobile, COLLECTED but not yet verified
--
-- V2 already modelled that split: users.phone_verified_at is its own nullable
-- column, distinct from user_private.phone_e164. This migration simply makes
-- the second fact arrive later than the first. When DLT clears, verification
-- backfills that column; nothing here has to be undone.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. The login address.
--
-- In user_private, not users. An email address identifies a person off-site
-- and belongs with the phone number, behind the deliberate join.
-- ---------------------------------------------------------------------------
ALTER TABLE user_private
    ADD COLUMN email      VARCHAR(254),

    -- SHA-256 of the normalised (lower-cased, trimmed) address, for the same
    -- reason phone_hash exists: account lookup and OTP rate limiting can then
    -- run without those code paths ever handling the address itself.
    --
    -- It also buys case-insensitive uniqueness for free. A UNIQUE on the hash
    -- of the normalised value cannot disagree with a UNIQUE on lower(email)
    -- the way two separately-maintained indexes eventually would.
    ADD COLUMN email_hash  VARCHAR(64);


-- Existing rows predate the column. There is no production data yet, and dev
-- data is reseeded, so a deterministic placeholder is honest: .invalid is the
-- reserved TLD (RFC 2606) that can never resolve, so these can never be mailed
-- and can never be mistaken for a real address.
UPDATE user_private
   SET email      = 'legacy-' || user_id || '@invalid.invalid',
       email_hash = encode(sha256(('legacy-' || user_id || '@invalid.invalid')::bytea), 'hex')
 WHERE email IS NULL;

ALTER TABLE user_private
    ALTER COLUMN email      SET NOT NULL,
    ALTER COLUMN email_hash SET NOT NULL;

ALTER TABLE user_private
    ADD CONSTRAINT user_private_email_uq      UNIQUE (email),
    ADD CONSTRAINT user_private_email_hash_uq UNIQUE (email_hash),

    -- Cheap shape check only. Real validation is LoginEmails, at the edge;
    -- this exists so garbage cannot be stored by a bug or a direct psql
    -- session. Same rule as the work_email check already on this table.
    ADD CONSTRAINT user_private_email_ck
        CHECK (email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$');


-- ---------------------------------------------------------------------------
-- 2. The mobile becomes optional.
--
-- An account is created the moment an email is verified, which is BEFORE the
-- profile step where the number is typed. So the column cannot be NOT NULL any
-- more without making account creation impossible.
--
-- Note it stays UNIQUE. Uniqueness matters MORE now, not less: an unverified
-- number that could be reused across accounts would let one person claim a
-- hundred. The trade-off is real and worth stating — if somebody mistypes YOUR
-- number, you cannot use it until that row is corrected. Rare, recoverable
-- through moderation, and far better than the alternative.
--
-- (NULLs do not collide in a Postgres UNIQUE, so many accounts may sit without
-- a number while no two may share one.)
--
-- V13's lesson applies here: ALTER COLUMN ... DROP NOT NULL does NOT drop a
-- DEFAULT. Neither of these columns has one, so there is nothing to strip, but
-- the check was made rather than assumed.
-- ---------------------------------------------------------------------------
ALTER TABLE user_private
    ALTER COLUMN phone_e164 DROP NOT NULL,
    ALTER COLUMN phone_hash DROP NOT NULL;

-- The format check must now tolerate absence. A CHECK passes on NULL by
-- default, but stating it makes the intent unmissable to the next reader.
ALTER TABLE user_private
    DROP CONSTRAINT user_private_phone_format_ck;

ALTER TABLE user_private
    ADD CONSTRAINT user_private_phone_format_ck
        CHECK (phone_e164 IS NULL OR phone_e164 ~ '^\+91[6-9][0-9]{9}$'),

    -- The pair is written together by UserPrivate.setPhoneE164, and this makes
    -- that indivisible at the storage layer too: a row with a number but no
    -- hash would break the lookup path silently.
    ADD CONSTRAINT user_private_phone_pair_ck
        CHECK ((phone_e164 IS NULL) = (phone_hash IS NULL));


COMMENT ON COLUMN user_private.email      IS 'Login identity. Verified by OTP before the account exists.';
COMMENT ON COLUMN user_private.phone_e164 IS 'Contact detail. NULL until collected; unverified until DLT allows SMS. The badge is users.phone_verified_at.';


-- ---------------------------------------------------------------------------
-- 3. The OTP tables stop being about phones.
--
-- WHY RENAME RATHER THAN ADD A SECOND TABLE. Everything that makes a six-digit
-- code safe lives in these tables and the service over them: the HMAC under a
-- server secret, the attempt cap, the resend cooldown, single use, two-
-- dimensional rate limiting. An `email_otp_challenges` table would mean a
-- second implementation of every one of those rules — and the second copy is
-- the one that quietly drifts, because it is the one nobody is looking at when
-- the first is changed.
--
-- A challenge is "a code issued to a subject". Whether the subject is reached
-- by SMS or by email is a delivery detail, so the column is named for what it
-- holds rather than for how it happens to be delivered today.
--
-- RENAME is safe here: it rewrites no rows, and dependent indexes follow the
-- column automatically.
-- ---------------------------------------------------------------------------
ALTER TABLE otp_challenges RENAME COLUMN phone_hash TO subject_hash;

ALTER INDEX otp_challenges_lookup_ix RENAME TO otp_challenges_subject_ix;

COMMENT ON COLUMN otp_challenges.subject_hash IS
    'SHA-256 of whatever the code was issued to — an E.164 number or a normalised email. Never the value itself.';


-- EMAIL_LOGIN joins the existing purposes. LOGIN (by SMS) is kept rather than
-- removed: it is the path that returns when DLT clears, and deleting working,
-- tested code to re-add it later is churn, not tidiness.
ALTER TABLE otp_challenges DROP CONSTRAINT otp_challenges_purpose_ck;

ALTER TABLE otp_challenges
    ADD CONSTRAINT otp_challenges_purpose_ck
        CHECK (purpose IN ('LOGIN', 'EMAIL_LOGIN', 'PHONE_CHANGE'));


-- The rate limiter gains a third scope. Counting email sends in the SAME
-- bucket as phone sends would be wrong in both directions: one address and one
-- number are different subjects, and an email send costs nothing like an SMS,
-- so the two deserve different budgets.
ALTER TABLE otp_rate_limits DROP CONSTRAINT otp_rate_limits_scope_ck;

ALTER TABLE otp_rate_limits
    ADD CONSTRAINT otp_rate_limits_scope_ck
        CHECK (scope IN ('PHONE', 'EMAIL', 'IP'));


COMMENT ON TABLE otp_challenges IS
    'Ephemeral. Purged after expiry; never stores a phone number or an email address, only its hash.';
