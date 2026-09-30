-- ---------------------------------------------------------------------------
-- Where a listing's location came from.
--
-- Dropping a pin was compulsory, and it was the single most common reason a
-- listing never went live: every other question on the six screens can be
-- answered from memory in a second, and this one asks somebody to find their
-- own building on a map and drag a marker onto it. On a phone. People stopped
-- there.
--
-- They do not have to any more. A recognised area supplies a usable centre by
-- itself — `areas.centroid`, already seeded for all 199 of them and already
-- good enough to seed a radius search when a SEARCHER picks an area rather
-- than dropping a pin. What is good enough to search from is good enough to
-- publish from.
--
-- WHY A COLUMN AND NOT JUST THE CENTROID.
--
-- The centre of Bandra West is not where the flat is, and `exact_location` is
-- a column whose entire meaning is that it holds the truth. Writing a
-- neighbourhood centre into it and saying nothing would leave the table
-- lying about itself — and the lie would be invisible, because a centroid
-- looks exactly like a pin. Every later reader would have to assume the worst
-- of every row, or, worse, would not think to.
--
-- So the row says which it is. A pin is a pin; a centroid says so.
--
-- The published circle stays honest either way: it has always meant "somewhere
-- around here", and for a centroid that is not merely defensible but literally
-- true. What changes is that code with a reason to care — anything measuring
-- a real distance, or deciding how much to trust the point — can now ask.
-- ---------------------------------------------------------------------------

ALTER TABLE listing_private
    ADD COLUMN location_source VARCHAR(20) NOT NULL DEFAULT 'PIN'
        CONSTRAINT listing_private_location_source_ck
        CHECK (location_source IN ('PIN', 'AREA_CENTROID'));

-- Every row that exists today got here by someone dragging a marker, because
-- until this migration there was no other way. The DEFAULT above is therefore
-- correct for the backfill as well as for new rows.
COMMENT ON COLUMN listing_private.location_source IS
    'PIN = the leaver placed the marker themselves. AREA_CENTROID = derived from areas.centroid because no pin was given; the point is the middle of the locality, not the building.';

COMMENT ON COLUMN listing_private.exact_location IS
    'The published circle is computed from this by trigger. Read location_source before treating it as the actual building: it may be a locality centre.';
