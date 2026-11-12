package com.freightfox.dispatch.distance;

import java.util.Objects;

/**
 * A fixed set of points with precomputed trigonometric values, so that Haversine distances
 * between any two of them need no trigonometric call at all. Built for hot loops (for example
 * the greedy assignment, which evaluates orders x vehicles distances).
 *
 * <p>It evaluates exactly the Haversine formula of {@link HaversineDistanceCalculator}, using the
 * angle-difference identity {@code sin((b - a) / 2) = sin(b/2)cos(a/2) - cos(b/2)sin(a/2)} with
 * the half-angle sines and cosines precomputed per point. Results agree with
 * {@link HaversineDistanceCalculator} up to floating point rounding (far below a millimetre).
 *
 * <p>Instances are immutable after construction and are safe to share between threads.
 */
public final class HaversineTable {

    private final double[] sinHalfLat;
    private final double[] cosHalfLat;
    private final double[] sinHalfLon;
    private final double[] cosHalfLon;
    private final double[] cosLat;

    /**
     * @param latitudesDeg  latitude of every point in decimal degrees
     * @param longitudesDeg longitude of every point in decimal degrees (same length)
     */
    public HaversineTable(double[] latitudesDeg, double[] longitudesDeg) {
        Objects.requireNonNull(latitudesDeg, "latitudesDeg");
        Objects.requireNonNull(longitudesDeg, "longitudesDeg");
        if (latitudesDeg.length != longitudesDeg.length) {
            throw new IllegalArgumentException("latitudes and longitudes must have the same length");
        }
        int n = latitudesDeg.length;
        sinHalfLat = new double[n];
        cosHalfLat = new double[n];
        sinHalfLon = new double[n];
        cosHalfLon = new double[n];
        cosLat = new double[n];
        for (int i = 0; i < n; i++) {
            double halfLat = Math.toRadians(latitudesDeg[i]) / 2.0;
            double halfLon = Math.toRadians(longitudesDeg[i]) / 2.0;
            sinHalfLat[i] = Math.sin(halfLat);
            cosHalfLat[i] = Math.cos(halfLat);
            sinHalfLon[i] = Math.sin(halfLon);
            cosHalfLon[i] = Math.cos(halfLon);
            cosLat[i] = Math.cos(2.0 * halfLat);
        }
    }

    /** Number of points. */
    public int size() {
        return cosLat.length;
    }

    /**
     * The Haversine term {@code h} between points {@code i} and {@code j}. Distance is strictly
     * increasing in {@code h}, so it can be compared directly when only the nearest point matters.
     */
    public double haversineTerm(int i, int j) {
        double sinHalfDeltaLat = sinHalfLat[j] * cosHalfLat[i] - cosHalfLat[j] * sinHalfLat[i];
        double sinHalfDeltaLon = sinHalfLon[j] * cosHalfLon[i] - cosHalfLon[j] * sinHalfLon[i];
        return sinHalfDeltaLat * sinHalfDeltaLat
                + cosLat[i] * cosLat[j] * sinHalfDeltaLon * sinHalfDeltaLon;
    }

    /** Great-circle distance in km between points {@code i} and {@code j}. */
    public double distanceKm(int i, int j) {
        return HaversineDistanceCalculator.kmFromHaversineTerm(haversineTerm(i, j));
    }
}
