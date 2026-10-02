package com.nexttenant.seo;

/**
 * Everything the {@code <head>} needs that differs between pages.
 *
 * <p>One object rather than six loose model attributes, because the six are
 * only ever right together. A page that sets a title and forgets the canonical
 * is a page Google may index under a filtered URL; a page that sets a
 * description and forgets {@code noindex} is a signed-in screen in the search
 * results. Passing them as a unit means the compiler asks for the ones that
 * matter and the template cannot half-apply them.
 *
 * <p><b>Paths, not URLs.</b> {@code canonicalPath} and {@code imagePath} are
 * site-relative — {@code /flats-in-powai}, not {@code https://…/flats-in-powai}.
 * The host lives in {@link com.nexttenant.common.SiteProperties} and is
 * prepended once, in the head fragment. A controller that built absolute URLs
 * itself would hardcode the host into every page, and staging would publish
 * canonicals pointing at production — which is the specific mistake that makes
 * a staging site outrank the real one.
 *
 * <p><b>Why noindex defaults to true.</b> Most pages on this site are signed-in
 * ones: the dashboard, the wizard, requests, moderation. A default of "index
 * me" means every page added later is published to search engines unless
 * somebody remembers otherwise, and the failure is invisible until a user's
 * dashboard URL turns up in a search result. The default here is the safe one
 * and the public pages opt in, which is the direction that fails quietly in the
 * right way: a missed opt-in costs traffic, a missed opt-out costs privacy.
 */
public record PageMeta(
        String title,
        String description,
        String canonicalPath,
        boolean noindex,
        String imagePath,
        String ogType,
        String jsonLd
) {

    /** Appended to every title. Short, because the page's own words matter more. */
    public static final String SUFFIX = " | Next Tenant";

    /**
     * The longest description worth writing.
     *
     * <p>Google truncates around 155-160 characters on desktop and less on
     * mobile. Longer is not penalised, it is simply cut mid-sentence — and a
     * description ending in "…" reads as carelessness in the one piece of copy
     * a searcher judges the result by.
     */
    public static final int MAX_DESCRIPTION = 155;

    public static Builder page(String title) {
        return new Builder(title);
    }

    /**
     * A page that must never be indexed, and needs nothing else said about it.
     *
     * <p>For signed-in screens and one-off flows. They still get a title,
     * because the title is what the browser tab and the back-button history
     * show, and "NextTenant" on nine tabs is its own small cruelty.
     */
    public static PageMeta noindex(String title) {
        return new Builder(title).noindex().build();
    }

    public static final class Builder {

        private final String title;
        private String description;
        private String canonicalPath;
        private boolean noindex = true;
        private String imagePath;
        private String ogType = "website";
        private String jsonLd;

        private Builder(String title) {
            this.title = title;
        }

        /**
         * The sentence under the title in a search result.
         *
         * <p>Trimmed rather than rejected when too long. A description is
         * marketing copy assembled from live data — a long area name and a long
         * room type can push a template over the limit — and failing the whole
         * page over a few characters would be the wrong trade. Cut at a word
         * boundary so it reads as a sentence that stops, not a string that was
         * severed.
         */
        public Builder describedAs(String description) {
            this.description = truncate(description);
            return this;
        }

        /**
         * The one URL this content should be indexed under.
         *
         * <p>Setting it also opts the page into indexing: a page worth naming a
         * canonical for is a page worth having in the index, and the two being
         * separate calls was an invitation to do one and not the other.
         */
        public Builder canonical(String path) {
            this.canonicalPath = path;
            this.noindex = false;
            return this;
        }

        /** Keeps the canonical but takes the page out of the index. */
        public Builder noindex() {
            this.noindex = true;
            return this;
        }

        /** As {@link #noindex()}, when the caller has a condition rather than a branch. */
        public Builder noindexIf(boolean condition) {
            if (condition) {
                this.noindex = true;
            }
            return this;
        }

        /**
         * The picture shown when the URL is pasted into WhatsApp.
         *
         * <p>Worth more here than the meta description is. A listing shared into
         * a flat-hunting group is a card with one photo on it, and a card with
         * no photo is a grey box people scroll past.
         */
        public Builder image(String path) {
            this.imagePath = path;
            return this;
        }

        /** "article" for guides; "website" for everything else. */
        public Builder ogType(String type) {
            this.ogType = type;
            return this;
        }

        /** Pre-rendered JSON-LD, from {@link JsonLd}. */
        public Builder structuredData(String json) {
            this.jsonLd = json;
            return this;
        }

        public PageMeta build() {
            return new PageMeta(title + SUFFIX, description, canonicalPath,
                    noindex, imagePath, ogType, jsonLd);
        }
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_DESCRIPTION) {
            return text;
        }
        String cut = text.substring(0, MAX_DESCRIPTION);
        int lastSpace = cut.lastIndexOf(' ');
        return lastSpace > MAX_DESCRIPTION / 2 ? cut.substring(0, lastSpace) : cut;
    }
}
