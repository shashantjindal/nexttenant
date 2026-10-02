package com.nexttenant.area;

/**
 * A Mumbai locality, or a sublocality inside one.
 *
 * <p>A record read through {@link AreaRepository} rather than a JPA entity:
 * areas are reference data that is read constantly and written never, and the
 * centroid is a geography column we always want as latitude/longitude rather
 * than as a JTS object.
 *
 * <p>One type for both levels, deliberately. A sublocality behaves identically
 * to a locality everywhere it is used — autocomplete, the wizard dropdown, the
 * pin map's "jump to this area". A separate type would mean every one of those
 * places handling two.
 *
 * @param parentName   the locality this sits inside, or null at the top level
 * @param radiusMetres how wide the locality is, so "in Bandra West" means
 *                     something sensible rather than a fixed arbitrary distance
 */
public record Area(
        short id,
        String name,
        String nearestStation,
        double latitude,
        double longitude,
        int radiusMetres,
        Short parentId,
        String parentName,

        /**
         * True when sublocalities sit under this area.
         *
         * <p>Which makes it a different kind of choice from its children:
         * ticking it in the filter selects every one of them. The UI needs
         * this to label it "All of Chembur" rather than letting it sit among
         * its own sublocalities looking like the narrowest option.
         */
        boolean hasChildren
) {

    /** True for a sublocality — Diamond Garden rather than Chembur. */
    public boolean isSublocality() {
        return parentId != null;
    }

    /**
     * The second line of an autocomplete row or a dropdown option.
     *
     * <p>A sublocality leads with its parent, because "Diamond Garden" alone
     * is not enough for somebody who does not already know Chembur — and the
     * people who most need the list are the ones who do not know the area.
     */
    public String subtitle() {
        return isSublocality()
                ? parentName + " · " + nearestStation + " station"
                : nearestStation + " station";
    }
}
