-- ============================================================================
-- V8 — Make the photo position constraint deferrable
--
-- V4 declared UNIQUE (listing_id, position) to guarantee there is exactly one
-- cover photo and no ambiguous ordering. That guarantee is right, but the
-- constraint as written makes REORDERING impossible:
--
--   UPDATE photos SET position = 1 WHERE id = 'a';   -- b already holds 1
--   UPDATE photos SET position = 0 WHERE id = 'b';   -- never reached
--
-- The first statement fails, because a non-deferrable UNIQUE is checked after
-- every statement, and the set is briefly inconsistent between the two.
--
-- The usual workarounds are ugly: shuffle everything to negative positions
-- first, or delete and re-insert. Both are extra statements that exist purely
-- to work around when the check happens.
--
-- DEFERRABLE INITIALLY DEFERRED moves the check to COMMIT. The set may be
-- inconsistent inside a transaction and must be consistent when it ends, which
-- is exactly the rule we actually want: no two photos share a position when
-- anybody can observe them.
--
-- The guarantee is unchanged. Only its timing is.
-- ============================================================================

ALTER TABLE photos
    DROP CONSTRAINT photos_listing_position_uq;

ALTER TABLE photos
    ADD CONSTRAINT photos_listing_position_uq
        UNIQUE (listing_id, position)
        DEFERRABLE INITIALLY DEFERRED;

COMMENT ON CONSTRAINT photos_listing_position_uq ON photos IS
    'Deferred to COMMIT so photos can be reordered within one transaction. The guarantee is unchanged: no two photos of a listing share a position once the transaction ends.';
