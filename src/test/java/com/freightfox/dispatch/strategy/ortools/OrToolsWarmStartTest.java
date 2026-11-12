package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.config.DispatchProperties;
import com.freightfox.dispatch.distance.DistanceCalculator;
import com.freightfox.dispatch.distance.HaversineDistanceCalculator;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.DispatchStrategy;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import com.freightfox.dispatch.strategy.VehicleRoute;
import com.freightfox.dispatch.strategy.greedy.GreedyPriorityDispatchStrategy;
import com.freightfox.dispatch.testdata.GeneratedDataset;
import com.freightfox.dispatch.testdata.TestDataGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The warm start from greedy and the priority guard of {@link OrToolsDispatchStrategy}, with the
 * real greedy strategy as seed and fallback.
 */
class OrToolsWarmStartTest {

    private static final Logger log = LoggerFactory.getLogger(OrToolsWarmStartTest.class);

    private static final Duration SMALL_TIME_LIMIT = Duration.ofMillis(300);
    /** Short on purpose: the warm start must hold the priority order at any limit, not only at 3 s. */
    private static final Duration REGRESSION_TIME_LIMIT = Duration.ofSeconds(1);
    private static final int REGRESSION_ORDERS = 1_000;
    private static final int REGRESSION_VEHICLES = 50;
    private static final long REGRESSION_SEED = 42L;
    private static final double ORIGIN_LAT = 28.6139;
    private static final double ORIGIN_LON = 77.2090;

    private final DistanceCalculator haversine = new HaversineDistanceCalculator();
    private final OrToolsNativeLoader realLoader = new OrToolsNativeLoader();

    // ---------------------------------------------------------------- regression (docs/PERFORMANCE.md)

    /**
     * 1,000 x 50 generated orders, seed 42, capacity-scarce (26,250 kg vs 15,124 kg). Before the warm
     * start, OR-Tools left 110 HIGH orders unassigned at 3 s while greedy served 285 HIGH orders.
     */
    @Test
    void capacityScarceDatasetNeverDropsMoreHighOrdersThanGreedy() {
        GeneratedDataset data = new TestDataGenerator().generate(REGRESSION_ORDERS, REGRESSION_VEHICLES,
                REGRESSION_SEED);
        CountingStrategy greedy = new CountingStrategy(greedy(REGRESSION_TIME_LIMIT));
        AtomicReference<CvrpSolution> solved = new AtomicReference<>();
        CvrpSolver recording = (model, seed, limit) -> {
            Optional<CvrpSolution> solution = new OrToolsCvrpSolver().solve(model, seed, limit);
            solution.ifPresent(solved::set);
            return solution;
        };

        DispatchResult result = strategy(REGRESSION_TIME_LIMIT, greedy, recording).plan(data.orders(), data.vehicles());
        DispatchResult greedyResult = greedy(REGRESSION_TIME_LIMIT).plan(data.orders(), data.vehicles());

        UnassignedCounts orTools = UnassignedCounts.of(result);
        UnassignedCounts reference = UnassignedCounts.of(greedyResult);
        log.info("Regression 1,000 x 50: OR-Tools assigned {} (unassigned H/M/L {}), {} km; greedy assigned {} "
                        + "(unassigned H/M/L {}), {} km", result.assignedCount(), orTools,
                String.format("%.2f", result.totalDistanceKm()), greedyResult.assignedCount(), reference,
                String.format("%.2f", greedyResult.totalDistanceKm()));

        assertThat(result.strategy()).as("the solver result itself passes the guard").isEqualTo(StrategyNames.ORTOOLS);
        assertThat(solved.get().warmStarted()).isTrue();
        assertThat(greedy.calls()).as("greedy runs once per plan").isEqualTo(1);
        assertThat(assignedOf(result, Priority.HIGH)).isGreaterThanOrEqualTo(assignedOf(greedyResult, Priority.HIGH));
        assertThat(UnassignedCounts.PRIORITY_ORDER.compare(orTools, reference)).isLessThanOrEqualTo(0);
        assertThat(result.assignedCount()).isGreaterThanOrEqualTo(greedyResult.assignedCount());
    }

    // ---------------------------------------------------------------- the seed

    @Test
    void solverIsSeededWithTheGreedyRoutesInModelIndices() {
        List<Vehicle> vehicles = List.of(vehicle("V1", 30, 0), vehicle("V2", 30, 1.0));
        DeliveryOrder tooHeavy = order("BIG", 31, Priority.HIGH, 0, 0.2);
        List<DeliveryOrder> orders = List.of(order("A", 10, Priority.HIGH, 0.01, 0), order("B", 10, Priority.MEDIUM,
                0.02, 0), tooHeavy, order("C", 10, Priority.LOW, 0, 1.01), order("D", 10, Priority.LOW, 0, 1.02));
        DispatchResult greedyPlan = greedy(SMALL_TIME_LIMIT).plan(orders, vehicles);
        List<List<String>> seededIds = new ArrayList<>();
        CvrpSolver capturing = (model, seed, limit) -> {
            seed.forEach(route -> seededIds.add(route.stream().map(i -> model.orders().get(i).getOrderId()).toList()));
            return new OrToolsCvrpSolver().solve(model, seed, limit);
        };

        DispatchResult result = strategy(SMALL_TIME_LIMIT, greedy(SMALL_TIME_LIMIT), capturing).plan(orders, vehicles);

        assertThat(seededIds).isEqualTo(greedyPlan.routes().stream()
                .map(r -> r.stops().stream().map(DeliveryOrder::getOrderId).toList()).toList());
        assertThat(seededIds).flatExtracting(ids -> ids).isNotEmpty().doesNotContain("BIG");
        assertThat(result.strategy()).isEqualTo(StrategyNames.ORTOOLS);
        assertThat(result.unassignedOrders()).singleElement().satisfies(u -> {
            assertThat(u.order()).isEqualTo(tooHeavy);
            assertThat(u.reason()).isEqualTo(UnassignmentReasons.EXCEEDS_EVERY_VEHICLE);
        });
    }

    // ---------------------------------------------------------------- the guard

    @Test
    void guardReturnsTheGreedyPlanWhenTheSolverDropsMoreHighOrders() {
        CountingStrategy greedy = new CountingStrategy(greedy(SMALL_TIME_LIMIT));
        CvrpSolver dropsEverything = (model, seed, limit) -> Optional.of(new CvrpSolution(
                seed.stream().map(route -> List.<Integer>of()).toList(), 0, "FAKE", true));

        DispatchResult result = strategy(SMALL_TIME_LIMIT, greedy, dropsEverything)
                .plan(SampleData.orders(), SampleData.vehicles());

        assertThat(result).isSameAs(greedy.lastResult());
        assertThat(result.strategy()).isEqualTo(StrategyNames.GREEDY);
        assertThat(greedy.calls()).isEqualTo(1);
    }

    @Test
    void guardReturnsTheGreedyPlanWhenOnlyAMediumOrderIsDroppedOnTopOfTheSeed() {
        // HIGH counts equal, one more MEDIUM unassigned: lexicographically worse -> greedy
        Vehicle vehicle = vehicle("V1", 100, 0);
        List<DeliveryOrder> orders = List.of(order("H", 10, Priority.HIGH, 0.01, 0),
                order("M", 10, Priority.MEDIUM, 0.02, 0));
        CountingStrategy greedy = new CountingStrategy(greedy(SMALL_TIME_LIMIT));
        CvrpSolver dropsMedium = (model, seed, limit) -> Optional.of(new CvrpSolution(
                List.of(List.of(indexOf(model, "H"))), 0, "FAKE", true));

        DispatchResult result = strategy(SMALL_TIME_LIMIT, greedy, dropsMedium).plan(orders, List.of(vehicle));

        assertThat(result).isSameAs(greedy.lastResult());
    }

    @Test
    void guardKeepsTheOrToolsPlanWhenItEqualsTheSeedCounts() {
        CountingStrategy greedy = new CountingStrategy(greedy(SMALL_TIME_LIMIT));
        CvrpSolver returnsTheSeed = (model, seed, limit) -> Optional.of(new CvrpSolution(seed, 0, "FAKE", true));

        DispatchResult result = strategy(SMALL_TIME_LIMIT, greedy, returnsTheSeed)
                .plan(SampleData.orders(), SampleData.vehicles());

        assertThat(result.strategy()).isEqualTo(StrategyNames.ORTOOLS);
        assertThat(UnassignedCounts.of(result)).isEqualTo(UnassignedCounts.of(greedy.lastResult()));
        assertThat(result.routes()).extracting(VehicleRoute::stops)
                .isEqualTo(greedy.lastResult().routes().stream().map(VehicleRoute::stops).toList());
    }

    @Test
    void noSolutionReturnsTheSeedPlanWithoutRunningGreedyAgain() {
        CountingStrategy greedy = new CountingStrategy(greedy(SMALL_TIME_LIMIT));

        DispatchResult result = strategy(SMALL_TIME_LIMIT, greedy, (model, seed, limit) -> Optional.empty())
                .plan(SampleData.orders(), SampleData.vehicles());

        assertThat(result).isSameAs(greedy.lastResult());
        assertThat(greedy.calls()).isEqualTo(1);
    }

    @Test
    void solverCrashReturnsTheSeedPlanWithoutRunningGreedyAgain() {
        CountingStrategy greedy = new CountingStrategy(greedy(SMALL_TIME_LIMIT));
        CvrpSolver crashing = (model, seed, limit) -> {
            throw new IllegalStateException("simulated solver crash");
        };

        DispatchResult result = strategy(SMALL_TIME_LIMIT, greedy, crashing)
                .plan(SampleData.orders(), SampleData.vehicles());

        assertThat(result).isSameAs(greedy.lastResult());
        assertThat(greedy.calls()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- bulkhead

    @Test
    void solvesBeyondTheConcurrencyLimitFallBackToGreedy() throws Exception {
        int limit = OrToolsDispatchStrategy.MAX_CONCURRENT_SOLVES;
        CountDownLatch entered = new CountDownLatch(limit);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger solverCalls = new AtomicInteger();
        CvrpSolver blocking = (model, seed, timeLimit) -> {
            solverCalls.incrementAndGet();
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(new CvrpSolution(seed, 0, "FAKE", true));
        };
        OrToolsDispatchStrategy strategy = strategy(SMALL_TIME_LIMIT, greedy(SMALL_TIME_LIMIT), blocking);
        ExecutorService pool = Executors.newFixedThreadPool(limit);
        try {
            List<Future<DispatchResult>> running = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                running.add(pool.submit(() -> strategy.plan(SampleData.orders(), SampleData.vehicles())));
            }
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("both slots taken").isTrue();

            DispatchResult overflow = strategy.plan(SampleData.orders(), SampleData.vehicles());

            assertThat(overflow.strategy()).isEqualTo(StrategyNames.GREEDY);
            assertThat(solverCalls.get()).isEqualTo(limit);
            release.countDown();
            for (Future<DispatchResult> future : running) {
                assertThat(future.get(10, TimeUnit.SECONDS).strategy()).isEqualTo(StrategyNames.ORTOOLS);
            }
            assertThat(strategy.plan(SampleData.orders(), SampleData.vehicles()).strategy())
                    .as("slots are released after use").isEqualTo(StrategyNames.ORTOOLS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int indexOf(CvrpModel model, String orderId) {
        for (int i = 0; i < model.numOrders(); i++) {
            if (model.orders().get(i).getOrderId().equals(orderId)) {
                return i;
            }
        }
        throw new IllegalArgumentException(orderId);
    }

    private static long assignedOf(DispatchResult result, Priority priority) {
        return result.routes().stream().flatMap(r -> r.stops().stream()).filter(o -> o.getPriority() == priority)
                .count();
    }

    private DispatchStrategy greedy(Duration timeLimit) {
        return new GreedyPriorityDispatchStrategy(haversine, properties(timeLimit));
    }

    private OrToolsDispatchStrategy strategy(Duration timeLimit, DispatchStrategy fallback, CvrpSolver solver) {
        return new OrToolsDispatchStrategy(haversine, properties(timeLimit), fallback, realLoader, solver);
    }

    private static DispatchProperties properties(Duration timeLimit) {
        return new DispatchProperties(1000, new DispatchProperties.Greedy(1000, Duration.ofMillis(25)),
                new DispatchProperties.OrTools(timeLimit, 1000));
    }

    private static Vehicle vehicle(String id, int capacity, double dLon) {
        return new Vehicle(id, capacity, ORIGIN_LAT, ORIGIN_LON + dLon, "Depot " + id);
    }

    private static DeliveryOrder order(String id, int weight, Priority priority, double dLat, double dLon) {
        return new DeliveryOrder(id, ORIGIN_LAT + dLat, ORIGIN_LON + dLon, "Address " + id, weight, priority);
    }

    /** Delegates to a real strategy, counting calls and keeping the last result. */
    private static final class CountingStrategy implements DispatchStrategy {

        private final DispatchStrategy delegate;
        private int calls;
        private DispatchResult lastResult;

        CountingStrategy(DispatchStrategy delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public DispatchResult plan(List<DeliveryOrder> orders, List<Vehicle> vehicles) {
            calls++;
            lastResult = delegate.plan(orders, vehicles);
            return lastResult;
        }

        int calls() {
            return calls;
        }

        DispatchResult lastResult() {
            return lastResult;
        }
    }
}
