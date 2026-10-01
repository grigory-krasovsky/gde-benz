package ru.gdebenz.fuel;

/** Small geo helpers: haversine distance and a bounding box around a point. */
public final class Geo {

    private static final double EARTH_RADIUS_M = 6_371_000.0;
    private static final double DEG_LAT_KM = 111.32;

    private Geo() {
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Returns {minLat, maxLat, minLon, maxLon} for a square-ish box of radiusKm around the point. */
    public static double[] boundingBox(double lat, double lon, double radiusKm) {
        double dLat = radiusKm / DEG_LAT_KM;
        double cos = Math.cos(Math.toRadians(lat));
        double dLon = radiusKm / (DEG_LAT_KM * (Math.abs(cos) < 1e-6 ? 1e-6 : cos));
        return new double[] {lat - dLat, lat + dLat, lon - dLon, lon + dLon};
    }
}
