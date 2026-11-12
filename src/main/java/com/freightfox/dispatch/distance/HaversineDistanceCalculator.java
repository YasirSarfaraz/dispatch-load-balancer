package com.freightfox.dispatch.distance;

import org.springframework.stereotype.Component;

/**
 * Great-circle distance on a spherical Earth using the Haversine formula.
 *
 * <pre>
 *   h = sin^2(dLat / 2) + cos(lat1) * cos(lat2) * sin^2(dLon / 2)
 *   d = 2 * R * asin(sqrt(h)),   R = 6371.0 km
 * </pre>
 *
 * {@code h} is clamped to [0, 1] so that rounding near antipodal points can never produce NaN.
 */
@Component
public class HaversineDistanceCalculator implements DistanceCalculator {

    /** Mean Earth radius in kilometres. */
    public static final double EARTH_RADIUS_KM = 6371.0;

    @Override
    public double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double sinHalfDeltaPhi = Math.sin((phi2 - phi1) / 2.0);
        double sinHalfDeltaLambda = Math.sin(Math.toRadians(lon2 - lon1) / 2.0);
        double h = sinHalfDeltaPhi * sinHalfDeltaPhi
                + Math.cos(phi1) * Math.cos(phi2) * sinHalfDeltaLambda * sinHalfDeltaLambda;
        return kmFromHaversineTerm(h);
    }

    /**
     * Converts the Haversine term {@code h} (see class Javadoc) into kilometres. The distance is
     * strictly increasing in {@code h}, so callers that only compare distances may compare
     * {@code h} directly and convert only when they need kilometres.
     *
     * @param h haversine term, nominally in [0, 1]; clamped to that range
     * @return the great-circle distance in km
     */
    public static double kmFromHaversineTerm(double h) {
        double clamped = Math.min(1.0, Math.max(0.0, h));
        return 2.0 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(clamped));
    }
}
