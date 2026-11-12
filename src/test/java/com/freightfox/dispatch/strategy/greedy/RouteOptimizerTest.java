package com.freightfox.dispatch.strategy.greedy;

import com.freightfox.dispatch.distance.HaversineTable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class RouteOptimizerTest {

    private static final long GENEROUS_BUDGET_NANOS = Duration.ofSeconds(10).toNanos();

    /** Points on the equator at the given longitudes; point 0 is the start. */
    private static HaversineTable equator(double... longitudes) {
        return new HaversineTable(new double[longitudes.length], longitudes);
    }

    private static RouteOptimizer.RouteDistances matrix(HaversineTable table) {
        int[] identity = new int[table.size()];
        Arrays.setAll(identity, i -> i);
        return new RouteOptimizer.MatrixDistances(table, identity);
    }

    @Test
    void twoOptRemovesACrossingInsideThePath() {
        // start 0, stops at lon 1, 3, 2, 4 -> reversing [3, 2] gives 1, 2, 3, 4.
        RouteOptimizer optimizer = new RouteOptimizer(100, GENEROUS_BUDGET_NANOS, 1024, 0);
        RouteOptimizer.RouteDistances distances = matrix(equator(0, 1, 3, 2, 4));
        int[] route = {0, 1, 2, 3, 4};
        double before = RouteOptimizer.pathLength(distances, route);

        optimizer.twoOpt(distances, route);

        assertThat(route).containsExactly(0, 1, 3, 2, 4);
        assertThat(RouteOptimizer.pathLength(distances, route)).isLessThan(before);
    }

    @Test
    void twoOptReversesTheTailBecauseTheEndIsFree() {
        // start 0, stops at lon 3, 2, 1 -> the open path is shortest as 1, 2, 3 (j = last stop case).
        RouteOptimizer optimizer = new RouteOptimizer(100, GENEROUS_BUDGET_NANOS, 1024, 0);
        RouteOptimizer.RouteDistances distances = matrix(equator(0, 3, 2, 1));
        int[] route = {0, 1, 2, 3};

        optimizer.twoOpt(distances, route);

        assertThat(route).containsExactly(0, 3, 2, 1);
    }

    @Test
    void twoOptKeepsTheStartFixed() {
        // Stops on both sides of the start: start at lon 0, stops at -1 and 5 and -2.
        RouteOptimizer optimizer = new RouteOptimizer(100, GENEROUS_BUDGET_NANOS, 1024, 0);
        RouteOptimizer.RouteDistances distances = matrix(equator(0, 5, -1, -2));
        int[] route = {0, 1, 2, 3};

        optimizer.twoOpt(distances, route);

        // 5, -1, -2 (12 degrees) becomes -2, -1, 5 (9 degrees); the start stays first.
        assertThat(route).containsExactly(0, 3, 2, 1);
    }

    @Test
    void twoOptStopsAfterMaxPasses() {
        RouteOptimizer onePass = new RouteOptimizer(1, GENEROUS_BUDGET_NANOS, 1024, 0);
        RouteOptimizer manyPasses = new RouteOptimizer(1000, GENEROUS_BUDGET_NANOS, 1024, 0);
        RouteOptimizer.RouteDistances distances = matrix(randomTable(new Random(8), 60));

        assertThat(onePass.twoOpt(distances, identity(60))).isEqualTo(1);
        assertThat(manyPasses.twoOpt(distances, identity(60))).isGreaterThan(1);
    }

    @Test
    void zeroTimeBudgetLeavesTheRouteUnchanged() {
        RouteOptimizer noBudget = new RouteOptimizer(100, Duration.ZERO);
        RouteOptimizer.RouteDistances distances = matrix(equator(0, 1, 3, 2, 4));
        int[] route = {0, 1, 2, 3, 4};

        noBudget.twoOpt(distances, route);

        assertThat(route).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void negativeOrNullBudgetDisablesTwoOptAndHugeBudgetDoesNotOverflow() {
        // Stops at lon 1, -1.1, 2, 3, 4 (8.2 degrees). Nearest-neighbour (1, 2, 3, 4, -1.1 = 9.1) is
        // rejected; only 2-opt finds -1.1, 1, 2, 3, 4 (6.2 degrees).
        HaversineTable table = equator(0, -1.1, 1, 2, 3, 4);
        int[] stops = {2, 1, 3, 4, 5};

        assertThat(new RouteOptimizer(100, Duration.ofMillis(-5)).optimize(table, 0, stops)).containsExactly(stops);
        assertThat(new RouteOptimizer(100, null).optimize(table, 0, stops)).containsExactly(stops);
        assertThat(new RouteOptimizer(100, Duration.ofSeconds(Long.MAX_VALUE)).optimize(table, 0, stops))
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void nearestNeighbourVisitsClosestFirstAndBreaksTiesByAssignmentOrder() {
        // Stops at lon 2, -1, 1, 3: nearest from 0 is -1 or 1 (tie -> -1, assigned first), then 1, 2, 3.
        RouteOptimizer.RouteDistances distances = matrix(equator(0, 2, -1, 1, 3));

        assertThat(RouteOptimizer.nearestNeighbour(distances, 4)).containsExactly(0, 2, 3, 1, 4);
    }

    @Test
    void optimizeMapsStopsBackToTablePointsAndHandlesTrivialRoutes() {
        // Table points: 0 = stop at lon 2, 1 = start at lon 0, 2 = stop at lon 1.
        HaversineTable table = equator(2, 0, 1);
        RouteOptimizer optimizer = new RouteOptimizer(100, Duration.ofSeconds(1));

        assertThat(optimizer.optimize(table, 1, new int[] {0, 2})).containsExactly(2, 0);
        assertThat(optimizer.optimize(table, 1, new int[] {0})).containsExactly(0);
        assertThat(optimizer.optimize(table, 1, new int[0])).isEmpty();
    }

    @Test
    void nearestNeighbourIsKeptOnlyWhenShorter() {
        // 2-opt disabled (zero budget) to isolate the nearest-neighbour step.
        RouteOptimizer nearestNeighbourOnly = new RouteOptimizer(100, 0L, 1024, 10_000);
        // Assignment order -1.1, 1, 2, 3, 4 = 6.2 degrees; NN hops to 1 first: 1, 2, 3, 4, -1.1 = 9.1 -> rejected.
        HaversineTable table = equator(0, -1.1, 1, 2, 3, 4);
        assertThat(nearestNeighbourOnly.optimize(table, 0, new int[] {1, 2, 3, 4, 5})).containsExactly(1, 2, 3, 4, 5);
        // Assignment order 4, 1, 3, 2 is a zig-zag; NN 1, 2, 3, 4 is shorter -> accepted.
        HaversineTable line = equator(0, 1, 2, 3, 4);
        assertThat(nearestNeighbourOnly.optimize(line, 0, new int[] {4, 1, 3, 2})).containsExactly(1, 2, 3, 4);
    }

    @Test
    void nearestNeighbourIsSkippedForVeryLongRoutes() {
        RouteOptimizer nearestNeighbourDisabled = new RouteOptimizer(100, 0L, 1024, 3);
        HaversineTable line = equator(0, 1, 2, 3, 4);
        assertThat(nearestNeighbourDisabled.optimize(line, 0, new int[] {4, 1, 3, 2})).containsExactly(4, 1, 3, 2);
    }

    @Test
    void onTheFlyDistancesGiveTheSameResultAsTheMatrix() {
        HaversineTable table = randomTable(new Random(21), 120);
        int[] stops = new int[119];
        Arrays.setAll(stops, i -> i + 1);
        RouteOptimizer withMatrix = new RouteOptimizer(1000, GENEROUS_BUDGET_NANOS, 1024, 10_000);
        RouteOptimizer withoutMatrix = new RouteOptimizer(1000, GENEROUS_BUDGET_NANOS, 2, 10_000);

        assertThat(withoutMatrix.optimize(table, 0, stops)).containsExactly(withMatrix.optimize(table, 0, stops));
    }

    @Test
    void neverReturnsALongerRouteThanTheInput() {
        Random random = new Random(13);
        RouteOptimizer optimizer = new RouteOptimizer(1000, Duration.ofSeconds(1));
        for (int trial = 0; trial < 50; trial++) {
            int size = 2 + random.nextInt(80);
            HaversineTable table = randomTable(random, size);
            int[] stops = new int[size - 1];
            Arrays.setAll(stops, i -> i + 1);

            int[] improved = optimizer.optimize(table, 0, stops);

            assertThat(improved).containsExactlyInAnyOrder(stops);
            assertThat(length(table, improved)).isLessThanOrEqualTo(length(table, stops) + RouteOptimizer.EPSILON_KM);
        }
    }

    private static double length(HaversineTable table, int[] stops) {
        double total = 0;
        int previous = 0;
        for (int stop : stops) {
            total += table.distanceKm(previous, stop);
            previous = stop;
        }
        return total;
    }

    private static int[] identity(int size) {
        int[] route = new int[size];
        Arrays.setAll(route, i -> i);
        return route;
    }

    private static HaversineTable randomTable(Random random, int size) {
        double[] lats = new double[size];
        double[] lons = new double[size];
        for (int i = 0; i < size; i++) {
            lats[i] = 28.4 + random.nextDouble() * 0.4;
            lons[i] = 77.0 + random.nextDouble() * 0.4;
        }
        return new HaversineTable(lats, lons);
    }
}
