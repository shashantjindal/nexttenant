package com.nexttenant.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * Builds JTS points for PostGIS {@code geography(Point, 4326)} columns.
 *
 * <p>This class exists for one reason: <strong>JTS and PostGIS order
 * coordinates (longitude, latitude), which is the reverse of how people say
 * them.</strong> A point built as {@code new Coordinate(19.0596, 72.8295)} is
 * not in Mumbai, it is in the Indian Ocean — and nothing throws, because both
 * numbers are valid coordinates. The failure is silent and presents as bad
 * data rather than as a bug.
 *
 * <p>So there is no bare two-argument constructor in this codebase. Points are
 * created through {@link #of(double, double)}, whose parameters are named and
 * range-checked, and which sets SRID 4326 — required, because the column type
 * enforces it and a mismatch is rejected at INSERT.
 */
public final class GeoPoints {

    /** WGS 84: the coordinate system GPS and the whole web use. */
    public static final int SRID_WGS84 = 4326;

    /**
     * Shared and thread-safe: GeometryFactory holds no mutable state, so one
     * instance serves the application.
     */
    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    private GeoPoints() {
    }

    /**
     * @param latitude  degrees north, -90 to 90 (Mumbai is about 19.08)
     * @param longitude degrees east, -180 to 180 (Mumbai is about 72.88)
     * @throws IllegalArgumentException if either value is out of range
     */
    public static Point of(double latitude, double longitude) {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException(
                    "latitude must be between -90 and 90, got " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException(
                    "longitude must be between -180 and 180, got " + longitude);
        }
        // Coordinate(x, y) == Coordinate(longitude, latitude). The whole point
        // of this class is that this line is written once, here.
        return FACTORY.createPoint(new Coordinate(longitude, latitude));
    }

    /** Latitude of a point, undoing the axis order so callers need not think about it. */
    public static double latitudeOf(Point point) {
        return point.getY();
    }

    /** Longitude of a point, undoing the axis order so callers need not think about it. */
    public static double longitudeOf(Point point) {
        return point.getX();
    }

    /**
     * Rough sanity check that a point is somewhere in the Mumbai metropolitan
     * region. Not a security control — a wrong-but-plausible pin is still
     * wrong — but it catches swapped axes, missing minus signs and pins
     * dropped in the sea before they reach the database.
     */
    public static boolean isWithinMumbai(Point point) {
        double lat = latitudeOf(point);
        double lng = longitudeOf(point);
        // Widened in V10 to cover the metropolitan region rather than the
        // island city: Panvel, Kalyan and Kharghar are all east of the old
        // 73.10 edge, and a flat there was being rejected as "not in Mumbai".
        //
        // A wider box is still a useful check. The failures it exists to catch
        // are gross ones — swapped axes put latitude near 72, a dropped minus
        // sign lands in the southern hemisphere — and both remain far outside.
        return lat >= 18.80 && lat <= 19.45
                && lng >= 72.75 && lng <= 73.25;
    }
}
