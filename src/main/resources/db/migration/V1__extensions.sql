-- ============================================================================
-- V1 — PostgreSQL extensions
--
-- Declares exactly which extensions this application depends on, so a fresh
-- database anywhere ends up identical regardless of what the host image
-- happened to pre-install.
-- ============================================================================

-- Geometry types, spatial indexes (GiST) and distance queries. Everything in
-- the search — radius, widen-the-radius, nearest station, similar-rooms —
-- rests on this.
CREATE EXTENSION IF NOT EXISTS postgis;

-- Trigram similarity, for fuzzy area-name autocomplete: "andhri" should still
-- find "Andheri". Backed by a GIN index, so it stays fast as areas grow.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- digest() / gen_random_bytes(), used later to hash OTP codes at rest so a
-- database leak does not hand out working login codes.
CREATE EXTENSION IF NOT EXISTS pgcrypto;


-- ---------------------------------------------------------------------------
-- Remove what the postgis Docker image enables on first init and we never use:
-- 34 tables of US Census address-geocoding data and 2 topology tables.
--
-- IF EXISTS / CASCADE keep this a harmless no-op on a managed Postgres where
-- these were never created in the first place.
-- ---------------------------------------------------------------------------
DROP EXTENSION IF EXISTS postgis_tiger_geocoder CASCADE;
DROP EXTENSION IF EXISTS postgis_topology CASCADE;
DROP EXTENSION IF EXISTS fuzzystrmatch CASCADE;

DROP SCHEMA IF EXISTS tiger      CASCADE;
DROP SCHEMA IF EXISTS tiger_data CASCADE;
DROP SCHEMA IF EXISTS topology   CASCADE;
