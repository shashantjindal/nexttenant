-- ---------------------------------------------------------------------------
-- Who is already living there.
--
-- "Anyone" answers who may TAKE the room. It says nothing about who is
-- already in the flat, and those are different questions that the wizard was
-- collapsing into one.
--
-- A room open to anyone in a flat of three men is a different proposition from
-- the same room in a mixed flat, and it is the first thing people ask once the
-- rent looks right. Leaving it unsaid does not avoid the question — it moves
-- it into a WhatsApp exchange after contact is released, which is the thing
-- this product exists to shorten.
--
-- Only meaningful when the room is open to anyone: "men only" already tells
-- you the flat is men. So this is asked as a follow-up rather than a field of
-- its own, and stays null otherwise.
-- ---------------------------------------------------------------------------

ALTER TABLE listings
    ADD COLUMN flatmate_makeup VARCHAR(20)
        CONSTRAINT listings_flatmate_makeup_ck
        CHECK (flatmate_makeup IN ('MEN', 'WOMEN', 'MIXED'));

COMMENT ON COLUMN listings.flatmate_makeup IS
    'Who already lives in the flat. Asked only when flatmate_gender is ANY, since the other two answers imply it.';
