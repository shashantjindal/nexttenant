-- ---------------------------------------------------------------------------
-- A label on each photo.
--
-- Eight photos of rooms in a flat are eight photos of rooms. A searcher
-- scrolling them cannot tell which bedroom is the one being handed over, which
-- bathroom they would share, or whether that balcony belongs to this room at
-- all — and those are exactly the questions that otherwise turn into a week of
-- messages, which is the thing this product exists to avoid.
--
-- Optional, deliberately. The photo flow is meant to be one tap: choose files,
-- done. A required caption per photo would put eight text fields in front of
-- somebody who has just been told the hard part is over.
--
-- Short on purpose. "Your room", "Shared bathroom", "Kitchen", "Building
-- entrance" — these are labels, not descriptions. The listing already has a
-- description field for sentences.
-- ---------------------------------------------------------------------------

ALTER TABLE photos
    ADD COLUMN label VARCHAR(60)
        CONSTRAINT photos_label_len_ck
        CHECK (label IS NULL OR length(trim(label)) BETWEEN 1 AND 60);

COMMENT ON COLUMN photos.label IS
    'Optional short caption shown under the photo, e.g. "Your room". Public: it appears on the listing page.';
