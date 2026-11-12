package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.distance.DistanceCalculator;

/**
 * Test-only Haversine (R = 6371.0 km). The production HaversineDistanceCalculator lives in another
 * module and is not available to these isolated tests.
 */
final class TestHaversine implements DistanceCalculator {

    private static final double EARTH_RADIUS_KM = 6371.0;

    @Override
    public double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
