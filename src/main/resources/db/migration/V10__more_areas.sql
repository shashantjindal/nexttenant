-- ============================================================================
-- V10 — the rest of Mumbai
--
-- V7 shipped 22 localities, which was enough to build a search page against and
-- far too few to list a flat with: anybody in Vikhroli, Kharghar or Mira Road
-- simply could not describe where they live.
--
-- A separate migration rather than an edit to V7. V7 has already run on
-- machines we do not control, and Flyway identifies a migration by its
-- checksum: changing the file makes every one of those databases refuse to
-- start with "checksum mismatch". The rule is not "never edit a migration" —
-- it is "never edit a migration that has run somewhere you cannot destroy".
--
-- ON CONFLICT DO NOTHING on the name, not the id, because the name is the
-- natural key: if a locality here duplicates one from V7 under a new id, the
-- id would let it through and searches would silently split across two rows.
--
-- CENTROID ACCURACY: these are hand-placed locality centres, good to a few
-- hundred metres. They are used to seed a radius search when somebody picks an
-- area instead of dropping a pin, where that is plenty. They are NOT a source
-- of truth for anything else, and before production they should be replaced
-- with geocoded values from an authoritative dataset.
-- ============================================================================

INSERT INTO areas (id, name, nearest_station, centroid, radius_metres) VALUES
    -- ---- South Mumbai ------------------------------------------------------
    ( 23, 'Colaba',           'Colaba',           ST_MakePoint(72.8147, 18.9067)::geography, 1500),
    ( 24, 'Cuffe Parade',     'Churchgate',       ST_MakePoint(72.8187, 18.9110)::geography, 1200),
    ( 25, 'Nariman Point',    'Churchgate',       ST_MakePoint(72.8236, 18.9256)::geography, 1000),
    ( 26, 'Fort',             'CSMT',             ST_MakePoint(72.8347, 18.9345)::geography, 1200),
    ( 27, 'Churchgate',       'Churchgate',       ST_MakePoint(72.8265, 18.9322)::geography,  900),
    ( 28, 'Marine Lines',     'Marine Lines',     ST_MakePoint(72.8236, 18.9440)::geography, 1000),
    ( 29, 'Girgaon',          'Charni Road',      ST_MakePoint(72.8180, 18.9540)::geography, 1200),
    ( 30, 'Malabar Hill',     'Charni Road',      ST_MakePoint(72.7950, 18.9540)::geography, 1400),
    ( 31, 'Walkeshwar',       'Charni Road',      ST_MakePoint(72.7940, 18.9480)::geography, 1000),
    ( 32, 'Napean Sea Road',  'Grant Road',       ST_MakePoint(72.8020, 18.9620)::geography, 1200),
    ( 33, 'Breach Candy',     'Grant Road',       ST_MakePoint(72.8050, 18.9670)::geography, 1000),
    ( 34, 'Peddar Road',      'Grant Road',       ST_MakePoint(72.8080, 18.9680)::geography, 1000),
    ( 35, 'Tardeo',           'Mumbai Central',   ST_MakePoint(72.8110, 18.9690)::geography, 1200),
    ( 36, 'Grant Road',       'Grant Road',       ST_MakePoint(72.8155, 18.9628)::geography, 1000),
    ( 37, 'Mumbai Central',   'Mumbai Central',   ST_MakePoint(72.8193, 18.9712)::geography, 1200),
    ( 38, 'Byculla',          'Byculla',          ST_MakePoint(72.8330, 18.9760)::geography, 1500),
    ( 39, 'Mazgaon',          'Dockyard Road',    ST_MakePoint(72.8420, 18.9650)::geography, 1300),
    ( 40, 'Sewri',            'Sewri',            ST_MakePoint(72.8570, 18.9980)::geography, 1400),
    ( 41, 'Mahalaxmi',        'Mahalaxmi',        ST_MakePoint(72.8230, 18.9825)::geography, 1200),
    ( 42, 'Parel',            'Parel',            ST_MakePoint(72.8400, 19.0000)::geography, 1300),
    ( 43, 'Prabhadevi',       'Prabhadevi',       ST_MakePoint(72.8280, 19.0180)::geography, 1200),
    ( 44, 'Dadar East',       'Dadar',            ST_MakePoint(72.8480, 19.0180)::geography, 1300),
    ( 45, 'Matunga East',     'Matunga',          ST_MakePoint(72.8560, 19.0270)::geography, 1300),
    ( 46, 'Matunga West',     'Matunga Road',     ST_MakePoint(72.8440, 19.0290)::geography, 1200),
    ( 47, 'Mahim',            'Mahim Junction',   ST_MakePoint(72.8410, 19.0410)::geography, 1400),
    ( 48, 'Wadala',           'Wadala Road',      ST_MakePoint(72.8620, 19.0170)::geography, 1600),
    ( 49, 'Antop Hill',       'Wadala Road',      ST_MakePoint(72.8650, 19.0230)::geography, 1300),
    ( 50, 'Sion',             'Sion',             ST_MakePoint(72.8600, 19.0400)::geography, 1600),

    -- ---- The Bandra to Borivali belt ---------------------------------------
    ( 51, 'Bandra Kurla Complex', 'Bandra',       ST_MakePoint(72.8680, 19.0660)::geography, 1500),
    ( 52, 'Khar East',        'Khar Road',        ST_MakePoint(72.8460, 19.0720)::geography, 1200),
    ( 53, 'Kalina',           'Santacruz',        ST_MakePoint(72.8630, 19.0730)::geography, 1400),
    ( 54, 'Vakola',           'Santacruz',        ST_MakePoint(72.8530, 19.0840)::geography, 1200),
    ( 55, 'Juhu',             'Vile Parle',       ST_MakePoint(72.8260, 19.1000)::geography, 1800),
    ( 56, 'Versova',          'Versova',          ST_MakePoint(72.8130, 19.1340)::geography, 1600),
    ( 57, 'Lokhandwala',      'Andheri',          ST_MakePoint(72.8270, 19.1420)::geography, 1500),
    ( 58, 'Oshiwara',         'Jogeshwari',       ST_MakePoint(72.8340, 19.1470)::geography, 1500),
    ( 59, 'Four Bungalows',   'D N Nagar',        ST_MakePoint(72.8250, 19.1270)::geography, 1200),
    ( 60, 'Seven Bungalows',  'Versova',          ST_MakePoint(72.8180, 19.1330)::geography, 1200),
    ( 61, 'Marol',            'Marol Naka',       ST_MakePoint(72.8790, 19.1120)::geography, 1600),
    ( 62, 'Sakinaka',         'Saki Naka',        ST_MakePoint(72.8880, 19.1030)::geography, 1500),
    ( 63, 'Chandivali',       'Saki Naka',        ST_MakePoint(72.8930, 19.1090)::geography, 1500),
    ( 64, 'Jogeshwari West',  'Jogeshwari',       ST_MakePoint(72.8400, 19.1350)::geography, 1800),
    ( 65, 'Jogeshwari East',  'Jogeshwari',       ST_MakePoint(72.8600, 19.1380)::geography, 1800),
    ( 66, 'Malad East',       'Malad',            ST_MakePoint(72.8620, 19.1880)::geography, 2200),
    ( 67, 'Kandivali West',   'Kandivali',        ST_MakePoint(72.8330, 19.2050)::geography, 2200),
    ( 68, 'Kandivali East',   'Kandivali',        ST_MakePoint(72.8700, 19.2080)::geography, 2200),
    ( 69, 'Borivali East',    'Borivali',         ST_MakePoint(72.8650, 19.2320)::geography, 2200),
    ( 70, 'Dahisar West',     'Dahisar',          ST_MakePoint(72.8540, 19.2490)::geography, 1800),
    ( 71, 'Dahisar East',     'Dahisar',          ST_MakePoint(72.8660, 19.2510)::geography, 1800),
    ( 72, 'Mira Road',        'Mira Road',        ST_MakePoint(72.8700, 19.2820)::geography, 2500),
    ( 73, 'Bhayandar',        'Bhayandar',        ST_MakePoint(72.8510, 19.3010)::geography, 2500),

    -- ---- Central line ------------------------------------------------------
    ( 74, 'Kurla East',       'Kurla',            ST_MakePoint(72.8900, 19.0680)::geography, 1600),
    ( 75, 'Ghatkopar West',   'Ghatkopar',        ST_MakePoint(72.9080, 19.0880)::geography, 1800),
    ( 76, 'Vidyavihar',       'Vidyavihar',       ST_MakePoint(72.8970, 19.0800)::geography, 1200),
    ( 77, 'Vikhroli West',    'Vikhroli',         ST_MakePoint(72.9200, 19.1100)::geography, 1600),
    ( 78, 'Vikhroli East',    'Vikhroli',         ST_MakePoint(72.9330, 19.1110)::geography, 1600),
    ( 79, 'Kanjurmarg',       'Kanjurmarg',       ST_MakePoint(72.9350, 19.1300)::geography, 1700),
    ( 80, 'Bhandup West',     'Bhandup',          ST_MakePoint(72.9330, 19.1450)::geography, 1800),
    ( 81, 'Bhandup East',     'Bhandup',          ST_MakePoint(72.9450, 19.1460)::geography, 1600),
    ( 82, 'Mulund East',      'Mulund',           ST_MakePoint(72.9620, 19.1740)::geography, 1800),
    ( 83, 'Thane East',       'Thane',            ST_MakePoint(72.9800, 19.1880)::geography, 2000),
    ( 84, 'Ghodbunder Road',  'Thane',            ST_MakePoint(72.9500, 19.2600)::geography, 3000),
    ( 85, 'Dombivli',         'Dombivli',         ST_MakePoint(73.0870, 19.2180)::geography, 2500),
    ( 86, 'Kalyan',           'Kalyan',           ST_MakePoint(73.1300, 19.2400)::geography, 3000),

    -- ---- Harbour line and Navi Mumbai --------------------------------------
    ( 87, 'Govandi',          'Govandi',          ST_MakePoint(72.9150, 19.0550)::geography, 1500),
    ( 88, 'Mankhurd',         'Mankhurd',         ST_MakePoint(72.9300, 19.0500)::geography, 1500),
    ( 89, 'Vashi',            'Vashi',            ST_MakePoint(72.9980, 19.0760)::geography, 2200),
    ( 90, 'Sanpada',          'Sanpada',          ST_MakePoint(73.0110, 19.0640)::geography, 1500),
    ( 91, 'Nerul',            'Nerul',            ST_MakePoint(73.0180, 19.0330)::geography, 2200),
    ( 92, 'Seawoods',         'Seawoods Darave',  ST_MakePoint(73.0180, 19.0180)::geography, 1800),
    ( 93, 'CBD Belapur',      'Belapur CBD',      ST_MakePoint(73.0380, 19.0220)::geography, 2000),
    ( 94, 'Kharghar',         'Kharghar',         ST_MakePoint(73.0690, 19.0470)::geography, 3000),
    ( 95, 'Airoli',           'Airoli',           ST_MakePoint(72.9960, 19.1590)::geography, 2200),
    ( 96, 'Ghansoli',         'Ghansoli',         ST_MakePoint(73.0070, 19.1230)::geography, 1800),
    ( 97, 'Kopar Khairane',   'Kopar Khairane',   ST_MakePoint(73.0090, 19.1030)::geography, 1800),
    ( 98, 'Panvel',           'Panvel',           ST_MakePoint(73.1100, 18.9890)::geography, 3000)
ON CONFLICT (name) DO NOTHING;
