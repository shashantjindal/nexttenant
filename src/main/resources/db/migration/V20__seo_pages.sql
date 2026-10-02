-- ============================================================================
-- V20 — The pages search engines land on
--
-- Three things, all in service of one idea: the area and guide pages are an
-- acquisition channel, so the data they need belongs in the database rather
-- than being derived at render time.
--
--   1. areas.slug and areas.station_slug — the LOOKUP TABLE for clean URLs.
--   2. areas.intro — a few hundred words of real prose per area.
--   3. guides — the articles.
--
-- WHY SLUGS ARE COLUMNS AND NOT COMPUTED PER REQUEST. The obvious
-- implementation is to slugify every area name on each request and compare. It
-- works, and it means the set of valid URLs is whatever a Java method happens
-- to produce today: a change to the slug algorithm silently 404s every indexed
-- URL, and nothing fails until Search Console reports it weeks later. As a
-- column, the URL space is data — visible, queryable, indexable.
--
-- It also answers the "infinite pages" problem directly. /flats-in-<anything>
-- resolves only if <anything> is a row here; everything else is a 404 rather
-- than a thin generated page. Google treats a site that answers 200 for
-- unlimited invented URLs as low quality, and rightly.
--
-- WHY GENERATED AND NOT JUST FILLED IN. A plain column has to be written by
-- every INSERT, so an area added later — by a migration, by a fixture, by hand
-- during an incident — either carries a slug somebody remembered to compute or
-- fails a NOT NULL check. Generated, it cannot be forgotten, cannot disagree
-- with the name it came from, and cannot be edited into something that no
-- longer matches. The rule lives in one place and Postgres enforces it.
-- ============================================================================


-- ---- 1. Slugs --------------------------------------------------------------

-- The expression mirrors seo/Slugs.of: lowercase, non-alphanumerics to hyphens,
-- collapse runs, trim the ends. LandingPageTest#slugRulesMatch pins the two
-- against each other, row by row, over the real table.
--
-- unaccent() is NOT used, deliberately: every name in this table is already
-- plain ASCII, and depending on that extension would make this migration fail
-- on a database where it is not installed, to solve a problem no row has. (It
-- is also not IMMUTABLE, so a generated column could not call it anyway.)
ALTER TABLE areas ADD COLUMN slug VARCHAR(80)
    GENERATED ALWAYS AS (
        trim(both '-' from regexp_replace(lower(name), '[^a-z0-9]+', '-', 'g'))
    ) STORED;

-- The station people name when they say where they live. NOT unique: a dozen
-- areas share Andheri, which is the entire point of /flats-near-andheri-station
-- as a page — it is broader than any one locality.
ALTER TABLE areas ADD COLUMN station_slug VARCHAR(80)
    GENERATED ALWAYS AS (
        trim(both '-' from regexp_replace(lower(nearest_station), '[^a-z0-9]+', '-', 'g'))
    ) STORED;

-- One area per URL. areas_name_uq already makes this true — the slug is a pure
-- function of the name — but only for names that differ by more than
-- punctuation. "Sector 17 Vashi" and "Sector-17 Vashi" are two legal names and
-- one slug, and without this the page for it resolves to whichever row the
-- planner happened to return first: a bug that changes between queries and is
-- discovered by a user.
ALTER TABLE areas ADD CONSTRAINT areas_slug_uq UNIQUE (slug);

-- The index that every area page load uses. The unique constraint above
-- already provides one for `slug`; this is for the station pages, where the
-- lookup returns several rows.
CREATE INDEX areas_station_slug_ix ON areas (station_slug);


-- ---- 2. Area prose ---------------------------------------------------------

-- WHY THIS COLUMN EXISTS AT ALL.
--
-- A page that is a heading and a grid of listings is, to a search engine, a
-- template with no content — and a few hundred of them is a site made mostly
-- of templates. Google's guidance on scaled content is explicit about this and
-- the penalty is not limited to the thin pages: it is applied to the domain.
--
-- So each area page carries real writing about the area. Written by a person,
-- not generated from the listings: "34 rooms available in Powai, with an
-- average rent of ₹28,400" is a sentence a machine wrote from a table, and
-- both Google and a reader can tell. The column is nullable because that
-- writing takes time — an area with no intro renders without one rather than
-- with filler.
ALTER TABLE areas ADD COLUMN intro TEXT
    CONSTRAINT areas_intro_length_ck CHECK (intro IS NULL OR length(intro) BETWEEN 200 AND 8000);

-- A one-line summary for the meta description, separate from the intro. The
-- first 155 characters of the prose make a poor description: good writing
-- opens with a sentence that sets up the next one, and a search result has no
-- next one.
ALTER TABLE areas ADD COLUMN meta_description VARCHAR(200);

ALTER TABLE areas ADD COLUMN intro_updated_at TIMESTAMPTZ;

COMMENT ON COLUMN areas.intro IS
    'Editor-written prose shown above the listing grid. Plain paragraphs separated by blank lines; no HTML, which is escaped on render.';
COMMENT ON COLUMN areas.slug IS
    'URL segment for /flats-in-<slug>. The set of valid area URLs IS this column.';


-- ---- 3. Guides -------------------------------------------------------------

CREATE TABLE guides (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The URL. Chosen by the author rather than derived from the title,
    -- because a title gets edited for clarity long after the URL has been
    -- shared and indexed, and the two should be free to diverge.
    slug             VARCHAR(120) NOT NULL,

    title            VARCHAR(200) NOT NULL,

    -- The meta description and the card on the index page. Written, not cut
    -- from the body, for the same reason areas.meta_description is.
    summary          VARCHAR(300) NOT NULL,

    -- Markdown. Stored as the author typed it and rendered on the way out, so
    -- the stored form stays editable and a rendering bug is fixable without a
    -- data migration. Never stored as HTML: HTML in a text column is a stored
    -- XSS waiting for somebody to render it unescaped.
    body             TEXT         NOT NULL,

    -- A person, because Article schema wants one and "Next Tenant" as an
    -- author is the kind of detail that makes a guide read as content
    -- marketing. Nullable, falling back to the site name.
    author_name      VARCHAR(120),

    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT guides_status_ck CHECK (status IN ('DRAFT', 'PUBLISHED')),

    -- Set when it first goes live and then left alone. Distinct from
    -- updated_at: Article schema carries both, and a guide whose publication
    -- date moves every time a typo is fixed looks to Google like a page that
    -- is republished rather than maintained.
    published_at     TIMESTAMPTZ,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT guides_slug_uq UNIQUE (slug),

    -- A PUBLISHED guide without a date would get no datePublished in its
    -- structured data and no date on the page. Enforced here rather than in
    -- the service, because the service is one of several ways a row can be
    -- written and this is a fact about the row.
    CONSTRAINT guides_published_has_date_ck
        CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

-- The index page lists published guides newest first; the sitemap asks the
-- same question. Partial, because draft guides are never listed and there is
-- no reason to carry them in the index.
CREATE INDEX guides_published_ix ON guides (published_at DESC)
    WHERE status = 'PUBLISHED';

COMMENT ON TABLE guides IS
    'Editorial articles at /guides/<slug>. Authored in the admin area; body is Markdown.';
