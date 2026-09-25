-- ============================================================================
-- V3 — Listings
--
-- Same principle as V2: public and private data do not share a table.
--
--   listings          what a stranger may see, including an APPROXIMATE point
--   listing_private   exact coordinates, full address, society name
--
-- The public search runs entirely against listings.approx_location, so the
-- exact point is never read by the browse path at all.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- Deterministic location jitter.
--
-- Returns a point offset 120-300m from the true location, in a direction and
-- by a distance derived from the listing UUID. Given the same id it always
-- returns the SAME point.
--
-- That stability is the entire security property. If the offset were random
-- per request, an observer could load the page repeatedly and average the
-- results: random noise cancels, and the true location falls out to within a
-- few metres. With one fixed offset there is only ever one sample to average.
--
-- IMMUTABLE tells the planner the result depends only on its inputs.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION approximate_point(exact_point geography, seed uuid)
RETURNS geography AS $fn$
DECLARE
    h        text;
    bearing  double precision;
    distance double precision;
BEGIN
    IF exact_point IS NULL THEN
        RETURN NULL;
    END IF;

    -- Salted so the mapping from id to offset cannot be recomputed by someone
    -- who merely knows the listing id. Changing this salt moves every circle.
    h := md5(seed::text || '|hh-location-jitter-v1');

    -- Casting hex to bit(32) to bigint yields an UNSIGNED 0..4294967295.
    bearing  := (('x' || substr(h, 1, 8))::bit(32)::bigint / 4294967295.0)
                * 2 * pi();

    -- 120m minimum so the offset is never negligible, 300m maximum so the
    -- circle still points at the right neighbourhood.
    distance := 120 + (('x' || substr(h, 9, 8))::bit(32)::bigint / 4294967295.0)
                * 180;

    RETURN ST_Project(exact_point, distance, bearing);
END;
$fn$ LANGUAGE plpgsql IMMUTABLE;


-- ---------------------------------------------------------------------------
-- listings — PUBLIC.
--
-- Every column here may appear on a page shown to a stranger.
-- ---------------------------------------------------------------------------
CREATE TABLE listings (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    leaver_user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    status                 VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT listings_status_ck
        CHECK (status IN ('DRAFT', 'LIVE', 'PAUSED', 'RENTED', 'EXPIRED', 'TAKEN_DOWN')),

    -- "Save and exit" in the six-step wizard resumes here.
    wizard_step            SMALLINT    NOT NULL DEFAULT 1
        CONSTRAINT listings_wizard_step_ck CHECK (wizard_step BETWEEN 1 AND 6),

    -- ---- Location, approximate only -------------------------------------
    area_name              VARCHAR(80),
    nearest_station        VARCHAR(80),
    minutes_to_station     SMALLINT
        CONSTRAINT listings_walk_ck CHECK (minutes_to_station BETWEEN 0 AND 120),

    -- The published circle centre. Maintained by trigger from
    -- listing_private.exact_location; never written by application code.
    approx_location        geography(Point, 4326),

    floor                  SMALLINT
        CONSTRAINT listings_floor_ck CHECK (floor BETWEEN -3 AND 120),
    has_lift               BOOLEAN,

    -- ---- The flat --------------------------------------------------------
    configuration          VARCHAR(20)
        CONSTRAINT listings_configuration_ck
        CHECK (configuration IN ('RK', 'BHK_1', 'BHK_2', 'BHK_3', 'BHK_4_PLUS')),

    room_type              VARCHAR(20)
        CONSTRAINT listings_room_type_ck
        CHECK (room_type IN ('PRIVATE_ROOM', 'SHARED_ROOM', 'MASTER_ROOM', 'ENTIRE_FLAT')),

    handover_type          VARCHAR(30)
        CONSTRAINT listings_handover_type_ck
        CHECK (handover_type IN ('ROOM_ONLY', 'ROOM_WITH_FURNITURE', 'ENTIRE_FLAT')),

    existing_flatmates     SMALLINT
        CONSTRAINT listings_flatmates_ck CHECK (existing_flatmates BETWEEN 0 AND 12),

    furnishing             VARCHAR(20)
        CONSTRAINT listings_furnishing_ck
        CHECK (furnishing IN ('UNFURNISHED', 'SEMI_FURNISHED', 'FULLY_FURNISHED')),

    -- Arrays keep the filter panel simple and index well with GIN. The tradeoff
    -- is no referential integrity: a typo becomes data. Acceptable while the
    -- vocabulary is small and written only by our own form.
    amenities              TEXT[]      NOT NULL DEFAULT '{}',
    perks                  TEXT[]      NOT NULL DEFAULT '{}',

    -- ---- Money, whole rupees ---------------------------------------------
    -- INTEGER, never FLOAT: 0.1 + 0.2 <> 0.3 in binary floating point, and a
    -- move-in total that is off by a paisa costs trust.
    monthly_rent_inr       INTEGER
        CONSTRAINT listings_rent_ck CHECK (monthly_rent_inr > 0),
    deposit_inr            INTEGER
        CONSTRAINT listings_deposit_ck CHECK (deposit_inr >= 0),
    maintenance_inr        INTEGER
        CONSTRAINT listings_maintenance_ck CHECK (maintenance_inr >= 0),
    maintenance_included   BOOLEAN     NOT NULL DEFAULT FALSE,
    brokerage_inr          INTEGER     NOT NULL DEFAULT 0
        CONSTRAINT listings_brokerage_ck CHECK (brokerage_inr >= 0),
    buyout_inr             INTEGER     NOT NULL DEFAULT 0
        CONSTRAINT listings_buyout_ck CHECK (buyout_inr >= 0),

    -- ---- Handover terms --------------------------------------------------
    -- "The things that usually take a week of messages to find out."
    owner_informed         BOOLEAN,
    deposit_paid_to        VARCHAR(20)
        CONSTRAINT listings_deposit_paid_to_ck
        CHECK (deposit_paid_to IN ('OWNER', 'EXISTING_TENANT', 'AGENCY')),
    registered_agreement   BOOLEAN,
    agreement_handling     VARCHAR(30)
        CONSTRAINT listings_agreement_handling_ck
        CHECK (agreement_handling IN ('TRANSFER_EXISTING', 'FRESH_AGREEMENT',
                                      'NAME_ADDED', 'NO_AGREEMENT')),
    notice_period_days     SMALLINT
        CONSTRAINT listings_notice_ck CHECK (notice_period_days BETWEEN 0 AND 365),
    bachelors_allowed      BOOLEAN,

    -- ---- Flatmates and house rules ---------------------------------------
    flatmate_age_min       SMALLINT
        CONSTRAINT listings_age_min_ck CHECK (flatmate_age_min BETWEEN 18 AND 99),
    flatmate_age_max       SMALLINT
        CONSTRAINT listings_age_max_ck CHECK (flatmate_age_max BETWEEN 18 AND 99),
    flatmate_gender        VARCHAR(20)
        CONSTRAINT listings_flatmate_gender_ck
        CHECK (flatmate_gender IN ('MALE', 'FEMALE', 'ANY')),
    flatmate_professions   VARCHAR(160),
    non_veg_allowed        BOOLEAN,
    pets_allowed           BOOLEAN,
    smoking_allowed        BOOLEAN,
    guests_allowed         BOOLEAN,
    house_rules_note       TEXT,

    -- ---- Lifecycle -------------------------------------------------------
    available_from         DATE,
    published_at           TIMESTAMPTZ,
    expires_at             TIMESTAMPTZ,
    rented_at              TIMESTAMPTZ,
    taken_down_at          TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT listings_age_range_ck CHECK (
        flatmate_age_min IS NULL
        OR flatmate_age_max IS NULL
        OR flatmate_age_min <= flatmate_age_max
    ),

    -- The invariant a single wide nullable table cannot express.
    --
    -- A DRAFT is half-finished by definition, so almost everything must be
    -- nullable. But a LIVE listing with no rent is corrupt data that will break
    -- a template at render time. A conditional CHECK gives us both: empty while
    -- drafting, complete the moment it is published.
    CONSTRAINT listings_live_complete_ck CHECK (
        status <> 'LIVE' OR (
            area_name          IS NOT NULL AND
            approx_location    IS NOT NULL AND
            configuration      IS NOT NULL AND
            room_type          IS NOT NULL AND
            furnishing         IS NOT NULL AND
            monthly_rent_inr   IS NOT NULL AND
            deposit_inr        IS NOT NULL AND
            available_from     IS NOT NULL AND
            published_at       IS NOT NULL AND
            expires_at         IS NOT NULL
        )
    )
);

CREATE TRIGGER listings_set_updated_at
    BEFORE UPDATE ON listings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- ---------------------------------------------------------------------------
-- listing_private — never rendered publicly.
-- ---------------------------------------------------------------------------
CREATE TABLE listing_private (
    listing_id      UUID PRIMARY KEY REFERENCES listings (id) ON DELETE CASCADE,

    -- The true pin the leaver dragged onto their building.
    exact_location  geography(Point, 4326),

    full_address    TEXT,

    -- Deliberately private: a society name IS an address. Publishing
    -- "Rustomjee Elanza, Malad West" makes the approximate circle decorative,
    -- because the name alone locates the building exactly.
    -- Released with the phone number once a request is accepted.
    society_name    VARCHAR(120),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER listing_private_set_updated_at
    BEFORE UPDATE ON listing_private
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- ---------------------------------------------------------------------------
-- Keep the public circle in step with the private truth.
--
-- Doing this in a trigger rather than in Java means approx_location can never
-- be stale, absent, or set by hand to the real coordinates. Application code
-- cannot forget, because application code is not involved.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sync_listing_approx_location()
RETURNS TRIGGER AS $fn$
BEGIN
    UPDATE listings
       SET approx_location = approximate_point(NEW.exact_location, NEW.listing_id)
     WHERE id = NEW.listing_id;
    RETURN NEW;
END;
$fn$ LANGUAGE plpgsql;

CREATE TRIGGER listing_private_sync_approx
    AFTER INSERT OR UPDATE OF exact_location ON listing_private
    FOR EACH ROW EXECUTE FUNCTION sync_listing_approx_location();


-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------

-- GiST, not B-tree. A B-tree orders values along one axis, which cannot answer
-- "near this point" in two dimensions. GiST indexes bounding boxes, so
-- ST_DWithin can discard most of the table before measuring anything.
CREATE INDEX listings_approx_location_ix ON listings USING GIST (approx_location);

-- Browse and sort are almost always over live listings only, so index just
-- those rows: a smaller index stays in memory and is faster to scan.
CREATE INDEX listings_live_published_ix ON listings (published_at DESC)
    WHERE status = 'LIVE';
CREATE INDEX listings_live_rent_ix ON listings (monthly_rent_inr)
    WHERE status = 'LIVE';
CREATE INDEX listings_live_available_ix ON listings (available_from)
    WHERE status = 'LIVE';

-- "Must have" filters test array containment (amenities @> '{LIFT,PARKING}').
-- GIN is the index type for that; a B-tree cannot help.
CREATE INDEX listings_amenities_ix ON listings USING GIN (amenities);

-- The dashboard lists a user's own listings.
CREATE INDEX listings_leaver_ix ON listings (leaver_user_id, status);

-- Expiry sweeps look for live listings past their date.
CREATE INDEX listings_expiry_ix ON listings (expires_at)
    WHERE status = 'LIVE';


COMMENT ON TABLE  listings        IS 'Public listing data. Safe to render to strangers.';
COMMENT ON TABLE  listing_private IS 'Exact location, address and society name. Released only on an accepted request.';
COMMENT ON COLUMN listings.approx_location IS 'Deterministic 120-300m offset from the true point. Trigger-maintained; never written by application code.';
