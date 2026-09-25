package com.househunting.area;

/**
 * A Mumbai locality.
 *
 * <p>A record read through {@link AreaRepository} rather than a JPA entity:
 * areas are reference data that is read constantly and written never, and the
 * centroid is a geography column we always want as latitude/longitude rather
 * than as a JTS object.
 *
 * @param radiusMetres how wide the locality is, so "in Bandra West" means
 *                     something sensible rather than a fixed arbitrary distance
 */
public record Area(
        short id,
        String name,
        String nearestStation,
        double latitude,
        double longitude,
        int radiusMetres
) {
    /** "Bandra West · Bandra station" — the autocomplete row. */
    public String subtitle() {
        return nearestStation + " station";
    }
}
