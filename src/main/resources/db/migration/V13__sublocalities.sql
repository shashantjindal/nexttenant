-- ============================================================================
-- V13 — Sublocalities
--
-- THE PROBLEM. `areas` was a flat list: one row per locality, one centre, one
-- radius. Chembur was a single row covering two kilometres, so somebody living
-- in Diamond Garden could only say "Chembur", and somebody who wanted Diamond
-- Garden could only ask for "Chembur". The names people actually use to say
-- where they live did not exist in the database, so nothing could offer them.
--
-- THE SHAPE. A parent_id on the same table, exactly two levels deep. Not a
-- separate `sublocalities` table, because a sublocality behaves identically to
-- a locality everywhere it is used — autocomplete, the wizard dropdown, the
-- search filter — and two tables would mean two of every query, UNIONed.
--
-- WHY THE CENTROID IS NOW NULLABLE. This is the honest part. We can name the
-- sublocalities; we cannot supply an accurate centre point for a hundred of
-- them without geocoding, and inventing coordinates would be worse than having
-- none: a wrong centre silently returns the wrong rooms, and nothing would ever
-- flag it. So a child may have no centroid, and AreaRepository falls back to
-- its parent's. Searching "Diamond Garden" searches Chembur until somebody
-- fills the column in — which is a smaller lie, and a visible one.
--
-- The eventual fix is to geocode these once from an authoritative source and
-- UPDATE the column. The schema is already ready for that; nothing else has to
-- change.
--
-- NAMES STAY GLOBALLY UNIQUE. listings.area_name stores a name, not an id, so a
-- name has to identify one place. That is why there is no bare "Union Park"
-- here — there is one in Chembur and one in Khar, and a listing saying "Union
-- Park" would be ambiguous. Where a name collides it is qualified.
-- ============================================================================

ALTER TABLE areas
    ADD COLUMN parent_id SMALLINT REFERENCES areas(id);

-- A child inherits its parent's centre when it has none of its own.
ALTER TABLE areas ALTER COLUMN centroid      DROP NOT NULL;
ALTER TABLE areas ALTER COLUMN radius_metres DROP NOT NULL;

-- DROP DEFAULT is separate from DROP NOT NULL, and forgetting it cost a test.
-- radius_metres was DEFAULT 1500, so a sublocality inserted without one got
-- 1500 rather than NULL — and COALESCE(child, parent) then found a value and
-- never consulted the parent. Chembur's 2000m locality produced 1500m
-- sublocalities, silently, with nothing null anywhere to hint at it.
--
-- With the default gone, a top-level insert that omits the radius now fails
-- the CHECK below instead of quietly getting an arbitrary number. That is the
-- better failure: 1500m is a guess, and a guess with no author is worse than
-- an error.
ALTER TABLE areas ALTER COLUMN radius_metres DROP DEFAULT;

-- But a TOP-LEVEL area must have both: there is nothing above it to fall back
-- to, and a null centroid there would make every radius search from it return
-- nothing, silently.
ALTER TABLE areas
    ADD CONSTRAINT areas_top_level_needs_centroid_ck
    CHECK (parent_id IS NOT NULL OR (centroid IS NOT NULL AND radius_metres IS NOT NULL));

CREATE INDEX areas_parent_ix ON areas (parent_id) WHERE parent_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Exactly two levels.
--
-- A self-referencing table with no depth limit is how you end up with
-- "Chembur > Diamond Garden > Diamond Garden Lane > Building 4" and a
-- dropdown nobody can render. A CHECK constraint cannot express this — it
-- cannot see other rows — so it is a trigger.
--
-- It also catches the two cases that would otherwise be silent corruption: a
-- row parented to itself, and a cycle of two.
-- ---------------------------------------------------------------------------
CREATE FUNCTION areas_enforce_two_levels() RETURNS trigger AS $$
BEGIN
    IF NEW.parent_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.parent_id = NEW.id THEN
        RAISE EXCEPTION 'area % cannot be its own parent', NEW.id;
    END IF;

    IF EXISTS (SELECT 1 FROM areas WHERE id = NEW.parent_id AND parent_id IS NOT NULL) THEN
        RAISE EXCEPTION
            'area % would be three levels deep; areas are exactly two levels', NEW.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER areas_two_levels
    BEFORE INSERT OR UPDATE OF parent_id ON areas
    FOR EACH ROW EXECUTE FUNCTION areas_enforce_two_levels();


-- ---------------------------------------------------------------------------
-- Reparent the entries from V10 that were always sublocalities.
--
-- Lokhandwala is part of Andheri West; Kalina is part of Santacruz East. They
-- went in as top-level rows because there was no other level to put them in.
-- They keep their ids, their names and their real centroids — only their
-- position in the tree changes, so no listing that already names one breaks.
-- ---------------------------------------------------------------------------
UPDATE areas SET parent_id =  3 WHERE name IN ('Lokhandwala', 'Four Bungalows', 'Versova', 'Seven Bungalows');
UPDATE areas SET parent_id =  4 WHERE name IN ('Marol', 'Sakinaka', 'Chandivali');
UPDATE areas SET parent_id = 13 WHERE name IN ('Juhu');
UPDATE areas SET parent_id =  2 WHERE name IN ('Bandra Kurla Complex');
UPDATE areas SET parent_id = 18 WHERE name IN ('Kalina', 'Vakola');
UPDATE areas SET parent_id = 64 WHERE name IN ('Oshiwara');
UPDATE areas SET parent_id = 48 WHERE name IN ('Antop Hill');
UPDATE areas SET parent_id = 11 WHERE name IN ('Ghodbunder Road');


-- ---------------------------------------------------------------------------
-- The sublocalities.
--
-- A STARTER SET, not a complete one — the well-known names for the areas where
-- "the whole locality" is least useful. It is data, so it grows by INSERT
-- rather than by code, and nothing below this line is load-bearing.
--
-- nearest_station repeats the parent's, because that is what people say.
-- ---------------------------------------------------------------------------
INSERT INTO areas (id, name, nearest_station, parent_id) VALUES
    -- Chembur (10)
    (100, 'Chembur Colony',        'Chembur',      10),
    (101, 'Diamond Garden',        'Chembur',      10),
    (102, 'RC Marg',               'Chembur',      10),
    (103, 'Tilak Nagar Chembur',   'Chembur',      10),
    (104, 'Ghatla',                'Chembur',      10),
    (105, 'Chedda Nagar',          'Chembur',      10),
    (106, 'Sindhi Society',        'Chembur',      10),
    (107, 'Basant Park',           'Chembur',      10),
    (108, 'Collector Colony',      'Chembur',      10),
    (109, 'Amar Mahal',            'Chembur',      10),
    (110, 'Vashi Naka',            'Chembur',      10),
    (111, 'Shell Colony',          'Chembur',      10),
    (112, 'Union Park Chembur',    'Chembur',      10),

    -- Bandra West (1)
    (113, 'Pali Hill',             'Bandra',        1),
    (114, 'Carter Road',           'Bandra',        1),
    (115, 'Bandstand',             'Bandra',        1),
    (116, 'Hill Road',             'Bandra',        1),
    (117, 'Linking Road',          'Bandra',        1),
    (118, 'Chapel Road',           'Bandra',        1),
    (119, 'Ranwar',                'Bandra',        1),
    (120, 'Bandra Reclamation',    'Bandra',        1),
    (121, 'Union Park Khar',       'Khar Road',    19),

    -- Bandra East (2)
    (122, 'Kherwadi',              'Bandra',        2),
    (123, 'MIG Colony',            'Bandra',        2),

    -- Andheri West (3)
    (124, 'Veera Desai Road',      'Andheri',       3),
    (125, 'D N Nagar',             'D N Nagar',     3),
    (126, 'Andheri Link Road',     'Andheri',       3),
    (127, 'J P Road',              'Andheri',       3),
    (128, 'Azad Nagar',            'Azad Nagar',    3),

    -- Andheri East (4)
    (129, 'Chakala',               'Andheri',       4),
    (130, 'J B Nagar',             'Andheri',       4),
    (131, 'Sahar',                 'Andheri',       4),
    (132, 'MIDC Andheri',          'Andheri',       4),
    (133, 'Kondivita',             'Andheri',       4),
    (134, 'Gundavali',             'Andheri',       4),

    -- Powai (5)
    (135, 'Hiranandani Gardens',   'Kanjurmarg',    5),
    (136, 'IIT Bombay',            'Kanjurmarg',    5),
    (137, 'Powai Lake',            'Kanjurmarg',    5),
    (138, 'Galleria',              'Kanjurmarg',    5),

    -- Malad West (7) and East (66)
    (139, 'Orlem',                 'Malad',         7),
    (140, 'Marve Road',            'Malad',         7),
    (141, 'Evershine Nagar',       'Malad',         7),
    (142, 'Mith Chowky',           'Malad',         7),
    (143, 'Liberty Garden',        'Malad',         7),
    (144, 'Kurar Village',         'Malad',        66),
    (145, 'Pushpa Park',           'Malad',        66),

    -- Goregaon East (8) and West (9)
    (146, 'Film City Road',        'Goregaon',      8),
    (147, 'Aarey Colony',          'Goregaon',      8),
    (148, 'Nesco',                 'Goregaon',      8),
    (149, 'Dindoshi',              'Goregaon',      8),
    (150, 'Motilal Nagar',         'Goregaon',      9),
    (151, 'Siddharth Nagar',       'Goregaon',      9),
    (152, 'Unnat Nagar',           'Goregaon',      9),

    -- Kandivali West (67) and East (68)
    (153, 'Charkop',               'Kandivali',    67),
    (154, 'Mahavir Nagar',         'Kandivali',    67),
    (155, 'Poisar',                'Kandivali',    67),
    (156, 'Thakur Village',        'Kandivali',    68),
    (157, 'Thakur Complex',        'Kandivali',    68),
    (158, 'Samata Nagar',          'Kandivali',    68),
    (159, 'Lokhandwala Township',  'Kandivali',    68),

    -- Borivali West (14) and East (69)
    (160, 'I C Colony',            'Borivali',     14),
    (161, 'Gorai',                 'Borivali',     14),
    (162, 'Eksar',                 'Borivali',     14),
    (163, 'Chikuwadi',             'Borivali',     14),
    (164, 'L T Road',              'Borivali',     14),
    (165, 'Magathane',             'Borivali',     69),
    (166, 'Devidas Lane',          'Borivali',     69),

    -- Thane West (11)
    (167, 'Hiranandani Estate',    'Thane',        11),
    (168, 'Majiwada',              'Thane',        11),
    (169, 'Kolshet',               'Thane',        11),
    (170, 'Manpada',               'Thane',        11),
    (171, 'Vartak Nagar',          'Thane',        11),
    (172, 'Pokhran Road No 1',     'Thane',        11),
    (173, 'Pokhran Road No 2',     'Thane',        11),
    (174, 'Naupada',               'Thane',        11),
    (175, 'Kasarvadavali',         'Thane',        11),
    (176, 'Waghbil',               'Thane',        11),

    -- Mulund West (21)
    (177, 'Sarvodaya Nagar',       'Mulund',       21),
    (178, 'Mulund Colony',         'Mulund',       21),
    (179, 'Veena Nagar',           'Mulund',       21),

    -- Ghatkopar East (20) and West (75)
    (180, 'Pant Nagar',            'Ghatkopar',    20),
    (181, 'Rajawadi',              'Ghatkopar',    20),
    (182, 'Garodia Nagar',         'Ghatkopar',    20),
    (183, 'Amrut Nagar',           'Ghatkopar',    75),
    (184, 'Barve Nagar',           'Ghatkopar',    75),

    -- Vile Parle East (12)
    (185, 'Nehru Road',            'Vile Parle',   12),
    (186, 'Hanuman Road',          'Vile Parle',   12),

    -- Dadar (15, 44)
    (187, 'Shivaji Park',          'Dadar',        15),
    (188, 'Kabutarkhana',          'Dadar',        15),
    (189, 'Hindu Colony',          'Dadar',        44),
    (190, 'Parsi Colony',          'Dadar',        44),

    -- Lower Parel (6) and Worli (22)
    (191, 'Kamala Mills',          'Lower Parel',   6),
    (192, 'Senapati Bapat Marg',   'Lower Parel',   6),
    (193, 'Worli Sea Face',        'Lower Parel',  22),
    (194, 'Worli Naka',            'Lower Parel',  22),
    (195, 'Worli Village',         'Lower Parel',  22),

    -- Navi Mumbai (89, 91, 94)
    (196, 'Sector 17 Vashi',       'Vashi',        89),
    (197, 'Palm Beach Road',       'Vashi',        89),
    (198, 'Nerul Sector 19',       'Nerul',        91),
    (199, 'Central Park Kharghar', 'Kharghar',     94),
    (200, 'Sector 20 Kharghar',    'Kharghar',     94)
ON CONFLICT (name) DO NOTHING;

COMMENT ON COLUMN areas.parent_id IS
    'NULL for a locality, set for a sublocality. Exactly two levels; enforced by areas_two_levels.';
COMMENT ON COLUMN areas.centroid IS
    'NULL is allowed only on a sublocality, which then inherits its parent''s centre. Fill these in when geocoded.';
