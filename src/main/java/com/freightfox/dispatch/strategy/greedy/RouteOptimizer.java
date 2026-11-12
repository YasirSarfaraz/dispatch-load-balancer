package com.freightfox.dispatch.strategy.greedy;

import com.freightfox.dispatch.distance.HaversineTable;

import java.time.Duration;

/**
 * Step 5 of the greedy algorithm: improves the visiting order of one vehicle's stops on an
 * <em>open</em> path (the vehicle's start is fixed, the route may end anywhere).
 *
 * <ol>
 *   <li>Nearest-neighbour ordering from the vehicle start (ties: the stop assigned first). It is
 *       kept only if it is shorter than the assignment order.</li>
 *   <li>2-opt: reversing the segment {@code [i..j]} replaces edges {@code (i-1, i)} and
 *       {@code (j, j+1)} by {@code (i-1, j)} and {@code (i, j+1)}; when {@code j} is the last stop
 *       there is no {@code (j, j+1)} edge. A move is applied only if it shortens the route by more
 *       than {@link #EPSILON_KM}. Passes repeat until no move improves, capped by a maximum
 *       number of passes and a wall-clock budget per vehicle.</li>
 * </ol>
 *
 * <p>Every accepted change strictly lowers the distance, so the result is never longer than the
 * assignment order. Up to {@link #DEFAULT_MATRIX_MAX_POINTS} points a distance matrix is
 * precomputed; above that, distances are evaluated on the fly from the {@link HaversineTable}.
 * Nearest-neighbour is O(k^2) and is skipped above {@link #DEFAULT_NEAREST_NEIGHBOUR_MAX_STOPS}.
 *
 * <p>The result is deterministic unless the time budget is exhausted (only for very long routes).
 */
public final class RouteOptimizer {

    /** Minimum gain (km) for a change to count as an improvement; guards against rounding loops. */
    static final double EPSILON_KM = 1e-9;

    /** Largest route (start + stops) for which a full distance matrix is precomputed (8 MB). */
    static final int DEFAULT_MATRIX_MAX_POINTS = 1024;

    /** Longest route for which the O(k^2) nearest-neighbour pass is attempted. */
    static final int DEFAULT_NEAREST_NEIGHBOUR_MAX_STOPS = 10_000;

    private final int maxPasses;
    private final long timeBudgetNanos;
    private final int matrixMaxPoints;
    private final int nearestNeighbourMaxStops;

    /**
     * @param maxPasses  maximum number of 2-opt improvement passes per vehicle
     * @param timeBudget maximum wall time of 2-opt per vehicle (zero or negative disables 2-opt)
     */
    public RouteOptimizer(int maxPasses, Duration timeBudget) {
        this(maxPasses, toNanos(timeBudget), DEFAULT_MATRIX_MAX_POINTS, DEFAULT_NEAREST_NEIGHBOUR_MAX_STOPS);
    }

    RouteOptimizer(int maxPasses, long timeBudgetNanos, int matrixMaxPoints, int nearestNeighbourMaxStops) {
        this.maxPasses = maxPasses;
        this.timeBudgetNanos = timeBudgetNanos;
        this.matrixMaxPoints = matrixMaxPoints;
        this.nearestNeighbourMaxStops = nearestNeighbourMaxStops;
    }

    /**
     * @param points     table holding the start and every stop
     * @param startPoint index of the vehicle's start in {@code points}
     * @param stopPoints indices of the stops in {@code points}, in assignment order (not modified)
     * @return the same stop indices in improved visiting order
     */
    public int[] optimize(HaversineTable points, int startPoint, int[] stopPoints) {
        int stopCount = stopPoints.length;
        if (stopCount < 2) {
            return stopPoints.clone();
        }
        int[] localToPoint = new int[stopCount + 1];
        localToPoint[0] = startPoint;
        System.arraycopy(stopPoints, 0, localToPoint, 1, stopCount);
        RouteDistances distances = localToPoint.length <= matrixMaxPoints
                ? new MatrixDistances(points, localToPoint)
                : new TableDistances(points, localToPoint);

        int[] route = new int[stopCount + 1];
        for (int p = 0; p <= stopCount; p++) {
            route[p] = p;
        }
        if (stopCount <= nearestNeighbourMaxStops) {
            int[] nearest = nearestNeighbour(distances, stopCount);
            if (pathLength(distances, nearest) < pathLength(distances, route) - EPSILON_KM) {
                route = nearest;
            }
        }
        twoOpt(distances, route);

        int[] result = new int[stopCount];
        for (int p = 1; p <= stopCount; p++) {
            result[p - 1] = localToPoint[route[p]];
        }
        return result;
    }

    /**
     * Nearest-neighbour tour over local points {@code 0..stopCount}, starting at 0.
     *
     * @return route array with {@code route[0] == 0}
     */
    static int[] nearestNeighbour(RouteDistances distances, int stopCount) {
        int[] route = new int[stopCount + 1];
        boolean[] visited = new boolean[stopCount + 1];
        int current = 0;
        for (int position = 1; position <= stopCount; position++) {
            int best = -1;
            double bestRank = Double.POSITIVE_INFINITY;
            for (int candidate = 1; candidate <= stopCount; candidate++) {
                if (visited[candidate]) {
                    continue;
                }
                double rank = distances.rank(current, candidate);
                if (best < 0 || rank < bestRank) {
                    best = candidate;
                    bestRank = rank;
                }
            }
            visited[best] = true;
            route[position] = best;
            current = best;
        }
        return route;
    }

    /**
     * 2-opt on an open path, in place. {@code route[0]} (the start) never moves.
     *
     * @return the number of passes performed
     */
    int twoOpt(RouteDistances distances, int[] route) {
        int last = route.length - 1;
        long startNanos = System.nanoTime();
        int passes = 0;
        boolean improved = true;
        while (improved && passes < maxPasses) {
            improved = false;
            passes++;
            for (int i = 1; i < last; i++) {
                if (System.nanoTime() - startNanos >= timeBudgetNanos) {
                    return passes;
                }
                int before = route[i - 1];
                double removedFirst = distances.km(before, route[i]);
                for (int j = i + 1; j <= last; j++) {
                    int segmentEnd = route[j];
                    double delta = distances.km(before, segmentEnd) - removedFirst;
                    if (j < last) {
                        int after = route[j + 1];
                        delta += distances.km(route[i], after) - distances.km(segmentEnd, after);
                    }
                    if (delta < -EPSILON_KM) {
                        reverse(route, i, j);
                        removedFirst = distances.km(before, route[i]);
                        improved = true;
                    }
                }
            }
        }
        return passes;
    }

    /** Open path length {@code route[0] -> route[1] -> ... -> route[last]} in km. */
    static double pathLength(RouteDistances distances, int[] route) {
        double total = 0.0;
        for (int p = 1; p < route.length; p++) {
            total += distances.km(route[p - 1], route[p]);
        }
        return total;
    }

    private static void reverse(int[] route, int from, int to) {
        for (int left = from, right = to; left < right; left++, right--) {
            int swap = route[left];
            route[left] = route[right];
            route[right] = swap;
        }
    }

    private static long toNanos(Duration budget) {
        if (budget == null || budget.isNegative()) {
            return 0L;
        }
        try {
            return budget.toNanos();
        } catch (ArithmeticException tooLong) {
            return Long.MAX_VALUE;
        }
    }

    /** Distances between the local points of one route (0 = start, 1..k = stops). */
    interface RouteDistances {

        /** Distance in km. */
        double km(int a, int b);

        /** Any value strictly increasing with distance; used where only comparisons matter. */
        double rank(int a, int b);
    }

    /** Precomputed symmetric matrix; for short routes. */
    static final class MatrixDistances implements RouteDistances {

        private final double[] matrix;
        private final int size;

        MatrixDistances(HaversineTable points, int[] localToPoint) {
            size = localToPoint.length;
            matrix = new double[size * size];
            for (int a = 0; a < size; a++) {
                for (int b = a + 1; b < size; b++) {
                    double km = points.distanceKm(localToPoint[a], localToPoint[b]);
                    matrix[a * size + b] = km;
                    matrix[b * size + a] = km;
                }
            }
        }

        @Override
        public double km(int a, int b) {
            return matrix[a * size + b];
        }

        @Override
        public double rank(int a, int b) {
            return matrix[a * size + b];
        }
    }

    /** On-the-fly evaluation; for routes too long for a matrix. */
    static final class TableDistances implements RouteDistances {

        private final HaversineTable points;
        private final int[] localToPoint;

        TableDistances(HaversineTable points, int[] localToPoint) {
            this.points = points;
            this.localToPoint = localToPoint;
        }

        @Override
        public double km(int a, int b) {
            return points.distanceKm(localToPoint[a], localToPoint[b]);
        }

        @Override
        public double rank(int a, int b) {
            return points.haversineTerm(localToPoint[a], localToPoint[b]);
        }
    }
}
