package com.freightfox.dispatch.distance;

/** Computes the distance between two coordinates given in decimal degrees. */
public interface DistanceCalculator {

    /**
     * @return distance in kilometres between (lat1, lon1) and (lat2, lon2); never negative,
     *         0 for identical points, symmetric.
     */
    double distanceKm(double lat1, double lon1, double lat2, double lon2);
}
