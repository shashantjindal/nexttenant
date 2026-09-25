-- ============================================================================
-- V7 — Mumbai areas
--
-- Reference data, not seed data. These are real localities the product needs
-- in every environment, so they belong in a migration — unlike the fictional
-- listings in DevDataSeeder, which exist only under the dev profile.
--
-- The rule: if production needs it to function, it is a migration. If it only
-- makes a developer's screen look populated, it is not.
-- ============================================================================

CREATE TABLE areas (
    id              SMALLINT     PRIMARY KEY,
    name            VARCHAR(80)  NOT NULL,

    -- The station people actually name when describing where they live. In
    -- Mumbai this is how location is communicated, far more than by postcode.
    nearest_station VARCHAR(80)  NOT NULL,

    -- Rough centre of the locality, used to seed a radius search when someone
    -- picks an area rather than dropping a pin.
    centroid        geography(Point, 4326) NOT NULL,

    -- Typical radius of the locality, so "within Bandra West" means something
    -- sensible rather than an arbitrary fixed distance.
    radius_metres   INTEGER      NOT NULL DEFAULT 1500
        CONSTRAINT areas_radius_ck CHECK (radius_metres BETWEEN 200 AND 10000),

    active          BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT areas_name_uq UNIQUE (name)
);

-- Fuzzy matching for the autocomplete: "andhri" should still find "Andheri".
-- GIN + trigrams, because a B-tree can only answer prefix queries and people
-- misspell the middle of words as often as the start.
CREATE INDEX areas_name_trgm_ix ON areas USING GIN (name gin_trgm_ops);
CREATE INDEX areas_station_trgm_ix ON areas USING GIN (nearest_station gin_trgm_ops);
CREATE INDEX areas_centroid_ix ON areas USING GIST (centroid);

INSERT INTO areas (id, name, nearest_station, centroid, radius_metres) VALUES
    ( 1, 'Bandra West',     'Bandra',       ST_MakePoint(72.8295, 19.0596)::geography, 1800),
    ( 2, 'Bandra East',     'Bandra',       ST_MakePoint(72.8479, 19.0607)::geography, 1600),
    ( 3, 'Andheri West',    'Andheri',      ST_MakePoint(72.8296, 19.1364)::geography, 2200),
    ( 4, 'Andheri East',    'Andheri',      ST_MakePoint(72.8697, 19.1136)::geography, 2200),
    ( 5, 'Powai',           'Kanjurmarg',   ST_MakePoint(72.9060, 19.1176)::geography, 2000),
    ( 6, 'Lower Parel',     'Lower Parel',  ST_MakePoint(72.8300, 18.9960)::geography, 1200),
    ( 7, 'Malad West',      'Malad',        ST_MakePoint(72.8480, 19.1860)::geography, 2400),
    ( 8, 'Goregaon East',   'Goregaon',     ST_MakePoint(72.8560, 19.1640)::geography, 2000),
    ( 9, 'Goregaon West',   'Goregaon',     ST_MakePoint(72.8399, 19.1663)::geography, 1800),
    (10, 'Chembur',         'Chembur',      ST_MakePoint(72.8990, 19.0620)::geography, 2000),
    (11, 'Thane West',      'Thane',        ST_MakePoint(72.9750, 19.1860)::geography, 3000),
    (12, 'Vile Parle East', 'Vile Parle',   ST_MakePoint(72.8440, 19.0990)::geography, 1400),
    (13, 'Vile Parle West', 'Vile Parle',   ST_MakePoint(72.8384, 19.1075)::geography, 1400),
    (14, 'Borivali West',   'Borivali',     ST_MakePoint(72.8560, 19.2300)::geography, 2400),
    (15, 'Dadar West',      'Dadar',        ST_MakePoint(72.8440, 19.0180)::geography, 1300),
    (16, 'Kurla West',      'Kurla',        ST_MakePoint(72.8790, 19.0700)::geography, 1800),
    (17, 'Santacruz West',  'Santacruz',    ST_MakePoint(72.8370, 19.0810)::geography, 1600),
    (18, 'Santacruz East',  'Santacruz',    ST_MakePoint(72.8560, 19.0800)::geography, 1600),
    (19, 'Khar West',       'Khar Road',    ST_MakePoint(72.8330, 19.0700)::geography, 1200),
    (20, 'Ghatkopar East',  'Ghatkopar',    ST_MakePoint(72.9080, 19.0860)::geography, 1800),
    (21, 'Mulund West',     'Mulund',       ST_MakePoint(72.9560, 19.1720)::geography, 2200),
    (22, 'Worli',           'Lower Parel',  ST_MakePoint(72.8170, 19.0100)::geography, 1500);

COMMENT ON TABLE areas IS 'Reference data. Production needs these; unlike DevDataSeeder listings, they are not fictional.';
