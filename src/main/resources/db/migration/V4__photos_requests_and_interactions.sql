-- ============================================================================
-- V4 — Photos, requests, shortlists, saved searches, reports
--
-- Completes the core schema. The contact-release rule lives here: a phone
-- number is readable only when an interest_request between those two parties
-- has reached CONNECTED.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- photos
--
-- Images live in object storage; this table holds only the key and metadata.
-- Never store image bytes in Postgres: it bloats every backup, bypasses CDN
-- caching, and makes the database the bottleneck for a purely static asset.
-- ---------------------------------------------------------------------------
CREATE TABLE photos (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id    UUID         NOT NULL REFERENCES listings (id) ON DELETE CASCADE,

    -- Key in object storage, e.g. listings/<uuid>/<uuid>.webp
    object_key    VARCHAR(255) NOT NULL,

    -- Position 0 IS the cover. Deliberately not an is_cover BOOLEAN, which
    -- would permit two covers or none — states the application would have to
    -- defend against forever. Order and cover are one fact, stored once.
    position      SMALLINT     NOT NULL
        CONSTRAINT photos_position_ck CHECK (position >= 0),

    width         SMALLINT,
    height        SMALLINT,
    byte_size     INTEGER,

    -- Set once the upload has been re-encoded and, critically, stripped of
    -- EXIF. Phone cameras embed GPS coordinates in photos: publishing an
    -- untouched image would leak the exact address that listing_private
    -- exists to protect. A NULL here means "not safe to serve yet".
    processed_at  TIMESTAMPTZ,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT photos_object_key_uq UNIQUE (object_key),
    -- Two photos cannot claim the same slot, so the ordering can never be
    -- ambiguous and there is exactly one cover.
    CONSTRAINT photos_listing_position_uq UNIQUE (listing_id, position)
);

CREATE INDEX photos_listing_ix ON photos (listing_id, position);

COMMENT ON COLUMN photos.processed_at IS 'Set after re-encode and EXIF strip. NULL means not safe to serve: raw phone photos carry GPS coordinates.';


-- ---------------------------------------------------------------------------
-- listing_stats — hot counters, deliberately in their own table.
--
-- Postgres UPDATE does not modify in place: MVCC writes a NEW row version and
-- marks the old one dead for VACUUM. Incrementing a counter on the 45-column
-- `listings` row copies all 45 columns on every page view, churning the very
-- table every search scans. A narrow row keeps that cost small and local.
-- ---------------------------------------------------------------------------
CREATE TABLE listing_stats (
    listing_id      UUID PRIMARY KEY REFERENCES listings (id) ON DELETE CASCADE,
    view_count      INTEGER NOT NULL DEFAULT 0
        CONSTRAINT listing_stats_views_ck CHECK (view_count >= 0),
    shortlist_count INTEGER NOT NULL DEFAULT 0
        CONSTRAINT listing_stats_shortlists_ck CHECK (shortlist_count >= 0),
    request_count   INTEGER NOT NULL DEFAULT 0
        CONSTRAINT listing_stats_requests_ck CHECK (request_count >= 0),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every listing gets a stats row at creation, so the application never has to
-- handle "no row yet" and dashboard reads are a plain join rather than a
-- LEFT JOIN with COALESCE scattered through the code.
CREATE OR REPLACE FUNCTION create_listing_stats()
RETURNS TRIGGER AS $fn$
BEGIN
    INSERT INTO listing_stats (listing_id) VALUES (NEW.id);
    RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

CREATE TRIGGER listings_create_stats
    AFTER INSERT ON listings
    FOR EACH ROW EXECUTE FUNCTION create_listing_stats();


-- ---------------------------------------------------------------------------
-- interest_requests — the contact-release mechanism.
--
-- This single row is the authority for whether two people may see each other's
-- phone number. There is no separate grants table: the release is symmetric
-- (accepting reveals both numbers to each other), so a second table would be
-- derived state that can drift out of agreement with this one.
--
--   PENDING    requester has asked, leaver has not answered
--   CONNECTED  leaver accepted; both numbers are mutually readable
--   DECLINED   leaver said no; nothing is released
--   WITHDRAWN  requester cancelled before an answer
--   REVOKED    access removed after the fact (moderation, or either party)
-- ---------------------------------------------------------------------------
CREATE TABLE interest_requests (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id         UUID        NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    requester_user_id  UUID        NOT NULL REFERENCES users (id)    ON DELETE CASCADE,

    -- "Leavers read maybe fifteen of these a week — specifics win."
    note               TEXT
        CONSTRAINT interest_requests_note_len_ck
        CHECK (note IS NULL OR length(note) <= 1000),

    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT interest_requests_status_ck
        CHECK (status IN ('PENDING', 'CONNECTED', 'DECLINED', 'WITHDRAWN', 'REVOKED')),

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at       TIMESTAMPTZ,
    connected_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One request per person per listing. Prevents both spam and the
    -- ambiguity of two rows disagreeing about whether contact was released.
    CONSTRAINT interest_requests_uq UNIQUE (listing_id, requester_user_id),

    -- CONNECTED without a timestamp would leave us unable to say WHEN access
    -- was granted, which is exactly the question asked after an incident.
    CONSTRAINT interest_requests_connected_ck
        CHECK (status <> 'CONNECTED' OR connected_at IS NOT NULL)
);

CREATE TRIGGER interest_requests_set_updated_at
    BEFORE UPDATE ON interest_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- "Requests received" on the dashboard, newest first.
CREATE INDEX interest_requests_listing_ix  ON interest_requests (listing_id, status, created_at DESC);
-- "My requests sent".
CREATE INDEX interest_requests_requester_ix ON interest_requests (requester_user_id, status, created_at DESC);

COMMENT ON TABLE interest_requests IS 'Authority for contact release. status = CONNECTED is the ONLY condition under which two users may read each other phone numbers.';


-- ---------------------------------------------------------------------------
-- shortlists
--
-- Composite primary key, no surrogate id: the pair IS the identity, and
-- double-hearting the same listing becomes structurally impossible.
-- ---------------------------------------------------------------------------
CREATE TABLE shortlists (
    user_id     UUID        NOT NULL REFERENCES users (id)    ON DELETE CASCADE,
    listing_id  UUID        NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT shortlists_pk PRIMARY KEY (user_id, listing_id)
);

-- The PK already indexes (user_id, listing_id). This covers the other
-- direction: "how many people shortlisted this listing".
CREATE INDEX shortlists_listing_ix ON shortlists (listing_id);


-- ---------------------------------------------------------------------------
-- saved_searches — "Save this search" in the filter panel.
-- ---------------------------------------------------------------------------
CREATE TABLE saved_searches (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                 VARCHAR(80),

    areas                TEXT[]      NOT NULL DEFAULT '{}',
    nearest_station      VARCHAR(80),
    rent_min_inr         INTEGER
        CONSTRAINT saved_searches_rent_min_ck CHECK (rent_min_inr >= 0),
    rent_max_inr         INTEGER
        CONSTRAINT saved_searches_rent_max_ck CHECK (rent_max_inr >= 0),
    move_in_from         DATE,
    configurations       TEXT[]      NOT NULL DEFAULT '{}',
    room_types           TEXT[]      NOT NULL DEFAULT '{}',
    furnishings          TEXT[]      NOT NULL DEFAULT '{}',
    flatmate_gender      VARCHAR(20)
        CONSTRAINT saved_searches_gender_ck
        CHECK (flatmate_gender IN ('MALE', 'FEMALE', 'ANY')),
    must_have_amenities  TEXT[]      NOT NULL DEFAULT '{}',

    notify               BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_run_at          TIMESTAMPTZ,

    -- A saved search with min above max silently matches nothing and looks
    -- like a bug in the search, not in the saved filter.
    CONSTRAINT saved_searches_rent_range_ck CHECK (
        rent_min_inr IS NULL OR rent_max_inr IS NULL OR rent_min_inr <= rent_max_inr
    )
);

CREATE INDEX saved_searches_user_ix ON saved_searches (user_id, created_at DESC);


-- ---------------------------------------------------------------------------
-- reports — "Two confirmed reports and the account is gone."
--
-- That rule is only as strong as the uniqueness behind it. Without the
-- constraint below, one person files two reports and removes a stranger's
-- account; moderation becomes a weapon rather than a safeguard.
-- ---------------------------------------------------------------------------
CREATE TABLE reports (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id        UUID        NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    reporter_user_id  UUID        NOT NULL REFERENCES users (id)    ON DELETE CASCADE,

    reason            VARCHAR(40) NOT NULL
        CONSTRAINT reports_reason_ck
        CHECK (reason IN ('IS_A_BROKER', 'ASKED_FOR_MONEY', 'LISTING_INACCURATE',
                          'ALREADY_RENTED', 'HARASSMENT', 'SPAM', 'OTHER')),
    detail            TEXT
        CONSTRAINT reports_detail_len_ck
        CHECK (detail IS NULL OR length(detail) <= 2000),

    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CONSTRAINT reports_status_ck
        CHECK (status IN ('OPEN', 'CONFIRMED', 'DISMISSED')),

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at       TIMESTAMPTZ,
    reviewed_by       UUID REFERENCES users (id) ON DELETE SET NULL,

    -- One report per person per listing. This is what makes "two confirmed
    -- reports" mean two different people.
    CONSTRAINT reports_uq UNIQUE (listing_id, reporter_user_id),

    -- A decided report must record who decided it and when.
    CONSTRAINT reports_reviewed_ck CHECK (
        status = 'OPEN' OR (reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL)
    )
);

-- The moderation queue: oldest open reports first.
CREATE INDEX reports_open_ix ON reports (created_at) WHERE status = 'OPEN';
CREATE INDEX reports_listing_ix ON reports (listing_id, status);
