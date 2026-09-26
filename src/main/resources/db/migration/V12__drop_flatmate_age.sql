-- ============================================================================
-- V12 — Remove the flatmate age range
--
-- The wizard asked "Youngest" and "Oldest" of the people already living in the
-- flat, and the listing page printed "22–32". Dropped at the product's
-- request, and it is the right call for reasons worth writing down:
--
--   It is a claim about OTHER PEOPLE. Everything else a leaver fills in is
--   about the flat or about themselves. Their flatmates' ages are personal
--   information about three people who never agreed to be listed, collected
--   from someone who has no standing to share it.
--
--   It reads as a filter even when it is not one. A range on a listing invites
--   "they want someone 22–32", which is a hair away from an age bar on housing.
--
--   Nobody used it to decide anything. The useful signal — "working
--   professionals", "students" — is flatmate_professions, which stays.
--
-- DROP COLUMN, not "stop writing to it". A column nothing reads is a column
-- somebody adds a read to in eighteen months, and the data in it is by then
-- years stale. If it is not wanted, it goes.
--
-- The constraints go with the columns automatically — a CHECK naming a dropped
-- column is dropped with it — but listings_age_range_ck spans BOTH columns, so
-- Postgres drops it when the first of them goes. Named explicitly here anyway,
-- with IF EXISTS, so the intent is on the record rather than implied.
-- ============================================================================

ALTER TABLE listings DROP CONSTRAINT IF EXISTS listings_age_range_ck;

ALTER TABLE listings DROP COLUMN IF EXISTS flatmate_age_min;
ALTER TABLE listings DROP COLUMN IF EXISTS flatmate_age_max;
