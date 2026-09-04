package ru.savefood.util;
/** Geodesy helpers, ported from utils.py {@code haversine} — the single implementation. */
public final class Geo {
    private Geo() {
    }
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    /** True only for a real WGS-84 latitude (not NaN, infinity or a wrapped value). */
    public static boolean isValidLatitude(Double latitude) {
        return latitude != null && Double.isFinite(latitude)
            && latitude >= -90.0 && latitude <= 90.0;
    }
    /** True only for a real WGS-84 longitude (not NaN, infinity or a wrapped value). */
    public static boolean isValidLongitude(Double longitude) {
        return longitude != null && Double.isFinite(longitude)
            && longitude >= -180.0 && longitude <= 180.0;
    }
    /** A complete, finite WGS-84 coordinate pair. */
    public static boolean isValidCoordinates(Double latitude, Double longitude) {
        return isValidLatitude(latitude) && isValidLongitude(longitude);
    }
    /** Great-circle distance in metres. */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dphi = Math.toRadians(lat2 - lat1);
        double dlambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dphi / 2) * Math.sin(dphi / 2)
            + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dlambda / 2) * Math.sin(dlambda / 2);
        return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
