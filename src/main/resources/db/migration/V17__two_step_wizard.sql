-- ---------------------------------------------------------------------------
-- The listing wizard goes from six screens to two.
--
-- It asked for 33 fields and 15 checkboxes to collect 7 required answers. The
-- thing it competes with is a leaver typing four lines into a WhatsApp group,
-- and against that bar the honest reading of the old flow is that it was
-- designed as though filling it in were free.
--
-- Nothing here deletes a column. The fields leaving the wizard — amenities,
-- house rules, agreement terms, floor, lift, maintenance, society name — move
-- to an optional screen offered AFTER the listing is live. Existing listings
-- keep everything they have, every detail page renders exactly as before, and
-- a leaver who wants to say more still can. The change is to what is demanded
-- before a room can go up, not to what can be recorded.
-- ---------------------------------------------------------------------------


-- ---- Room type: hall --------------------------------------------------
--
-- Sleeping in the living room is a real Mumbai arrangement, priced quite
-- differently from a room of your own, and it had nowhere to go: a leaver
-- doing it had to choose between "shared room" and "private room" and be wrong
-- either way.
--
-- MASTER_ROOM stays permitted although the new wizard does not offer it.
-- Listings already carry it, and narrowing the constraint under live rows
-- would turn an existing listing into one the database refuses to save.
ALTER TABLE listings
    DROP CONSTRAINT listings_room_type_ck;

ALTER TABLE listings
    ADD CONSTRAINT listings_room_type_ck
        CHECK (room_type IN ('PRIVATE_ROOM', 'SHARED_ROOM', 'MASTER_ROOM',
                             'HALL', 'ENTIRE_FLAT'));


-- ---- Flat type --------------------------------------------------------
--
-- A gated society and a standalone building are different products in Mumbai:
-- different security, different rules about who may visit and when, different
-- attitudes to tenants. People ask before they visit, so the listing should
-- say.
ALTER TABLE listings
    ADD COLUMN flat_type VARCHAR(20)
        CONSTRAINT listings_flat_type_ck
        CHECK (flat_type IN ('STANDALONE', 'GATED_SOCIETY'));

COMMENT ON COLUMN listings.flat_type IS
    'STANDALONE = an individual building. GATED_SOCIETY = a complex with gates and a watchman.';


-- ---- Description ------------------------------------------------------
--
-- The one place a leaver writes in their own words. Everything else on a
-- listing is a dropdown, and dropdowns cannot say "the flatmates are easy
-- going and we cook together on Sundays" — which is the part that makes
-- somebody choose this room over an identical one.
--
-- Length capped in the database as well as the form, because a form limit is
-- a limit on one path to the column.
ALTER TABLE listings
    ADD COLUMN description TEXT
        CONSTRAINT listings_description_len_ck
        CHECK (description IS NULL OR length(description) <= 1500);


-- ---- The Maps link ----------------------------------------------------
--
-- Replaces dragging a pin, which asked somebody to find their own building on
-- a map on a phone. Pasting a link from Google Maps is a thing people already
-- know how to do.
--
-- It lives in listing_private and NOT on listings, and that is the whole point
-- rather than a detail: a Maps link IS an address. Published, it would make
-- the approximate circle decorative — the same reason society_name has always
-- been private. It is released with the phone number when a request is
-- accepted, through the same choke point as everything else.
ALTER TABLE listing_private
    ADD COLUMN maps_link TEXT
        CONSTRAINT listing_private_maps_link_len_ck
        CHECK (maps_link IS NULL OR length(maps_link) <= 2048);

COMMENT ON COLUMN listing_private.maps_link IS
    'A Google Maps link pasted by the leaver. PRIVATE: an address by another name. Released with contact details, never published.';


-- ---- Existing drafts --------------------------------------------------
--
-- A draft abandoned on old step 5 would resume at a screen that no longer
-- exists. Clamped to the last step the new flow has, so every half-finished
-- listing still opens somewhere real.
--
-- Upwards as well as downwards is deliberate: step is only ever used to decide
-- where to resume, and sending somebody back to screen one to re-answer
-- questions they already answered is its own way of losing a listing.
UPDATE listings
   SET wizard_step = 2
 WHERE wizard_step > 2;
