package com.nexttenant.seo;

/**
 * An area as its landing page needs it.
 *
 * <p>Separate from {@link com.nexttenant.area.Area} rather than adding four
 * columns to it. {@code Area} is read on every autocomplete keystroke and
 * serialised into the suggestion JSON; carrying a few hundred words of prose
 * through that path would be bytes down the wire on every keystroke, for a
 * field the autocomplete never shows.
 *
 * @param intro           editor-written prose, or null while nobody has written
 *                        any — in which case the page shows its other content
 *                        rather than filler
 * @param metaDescription the search-result sentence, written separately from
 *                        the intro because the first 155 characters of good
 *                        prose make a poor standalone description
 */
public record AreaLanding(
        short id,
        String name,
        String slug,
        String nearestStation,
        String stationSlug,
        String parentName,
        String intro,
        String metaDescription
) {

    public boolean isSublocality() {
        return parentName != null;
    }

    public boolean hasIntro() {
        return intro != null && !intro.isBlank();
    }

    /** "/flats-in-powai" */
    public String path() {
        return "/flats-in-" + slug;
    }

    /** "/shared-flats-in-powai" */
    public String sharedPath() {
        return "/shared-flats-in-" + slug;
    }

    /** "/flats-in-powai-under-25000" */
    public String pathUnder(int budget) {
        return path() + "-under-" + budget;
    }
}
