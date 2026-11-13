package com.freightfox.dispatch.performance;

import com.freightfox.dispatch.config.DispatchProperties;
import com.freightfox.dispatch.distance.DistanceCalculator;
import com.freightfox.dispatch.dto.response.DispatchPlanResponse;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.service.DispatchService;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.UpsertResult;
import com.freightfox.dispatch.service.VehicleService;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.DispatchStrategy;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.ortools.OrToolsDispatchStrategy;
import com.freightfox.dispatch.strategy.ortools.OrToolsNativeLoader;
import com.freightfox.dispatch.strategy.registry.DispatchStrategyRegistry;
import com.freightfox.dispatch.testdata.GeneratedDataset;
import com.freightfox.dispatch.testdata.TestDataGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance scenarios of section 11.2, run only with {@code ./mvnw verify -Pperformance}.
 *
 * <p>For every scenario: generate data with the production {@link TestDataGenerator} (fixed seed),
 * upsert it through the real {@link VehicleService} / {@link OrderService} into the H2 database,
 * load it back, time the strategy bean's {@code plan} on the loaded entities, assert every
 * invariant, then time the end-to-end {@link DispatchService#generatePlan} as well. The results
 * are written to {@code target/performance-report.md} by {@link PerformanceReport} in {@code @AfterAll}.
 */
@Tag("performance")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DispatchPerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(DispatchPerformanceTest.class);

    static final long SEED = 42L;
    private static final long NANOS_PER_MS = 1_000_000L;

    private static final int WARM_UP_ORDERS = 10_000;
    private static final int WARM_UP_VEHICLES = 200;
    private static final int WARM_UP_ITERATIONS = 6;
    private static final int WARM_UP_UPSERT_ORDERS = 2_000;
    private static final int WARM_UP_UPSERT_VEHICLES = 50;
    /** Extra OR-Tools time limits (besides the configured one) for the sensitivity check at the cap. */
    private static final List<Duration> SENSITIVITY_TIME_LIMITS = List.of(Duration.ofSeconds(10), Duration.ofSeconds(30));

    /** Generous margins so a slow CI machine does not make the build flaky. */
    private static final long END_TO_END_EXTRA_MS = 15_000;
    private static final long ORTOOLS_OVERHEAD_MS = 10_000;

    private static final Scenario GREEDY_1K = new Scenario(StrategyNames.GREEDY, 1_000, 50, 15_000, 2_000);
    private static final Scenario GREEDY_10K = new Scenario(StrategyNames.GREEDY, 10_000, 200, 30_000, 5_000);
    private static final Scenario GREEDY_50K = new Scenario(StrategyNames.GREEDY, 50_000, 500, 60_000, 15_000);
    private static final int ORTOOLS_SMALL_ORDERS = 200;
    private static final int ORTOOLS_SMALL_VEHICLES = 20;
    private static final int ORTOOLS_CAP_ORDERS = 1_000;
    private static final int ORTOOLS_CAP_VEHICLES = 50;
    private static final long ORTOOLS_UPSERT_LIMIT_MS = 15_000;

    private final TestDataGenerator generator;
    private final OrderService orderService;
    private final VehicleService vehicleService;
    private final DispatchService dispatchService;
    private final DispatchStrategyRegistry registry;
    private final DispatchProperties properties;
    private final DistanceCalculator distanceCalculator;
    private final OrToolsNativeLoader nativeLoader;
    private final DataSource dataSource;
    private final Environment environment;
    private final PerformanceReport report = PerformanceReport.get();
    /** The OR-Tools run at the cap, kept for the time-limit sensitivity check (null if not run). */
    private Measured orToolsAtCap;

    @Autowired
    DispatchPerformanceTest(TestDataGenerator generator, OrderService orderService, VehicleService vehicleService,
                            DispatchService dispatchService, DispatchStrategyRegistry registry,
                            DispatchProperties properties, DistanceCalculator distanceCalculator,
                            OrToolsNativeLoader nativeLoader, DataSource dataSource, Environment environment) {
        this.generator = generator;
        this.orderService = orderService;
        this.vehicleService = vehicleService;
        this.dispatchService = dispatchService;
        this.registry = registry;
        this.properties = properties;
        this.distanceCalculator = distanceCalculator;
        this.nativeLoader = nativeLoader;
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @BeforeAll
    void describeRun() throws SQLException {
        report.setDatabase(databaseDescription());
        report.putSettings("Dispatch scenarios", settings());
    }

    @AfterAll
    void writeReport() {
        clearTables();
        report.write();
    }

    @Test
    @Order(1)
    void warmUpGreedyAndLoadOrToolsNatives() {
        GeneratedDataset data = generator.generate(WARM_UP_ORDERS, WARM_UP_VEHICLES, SEED);
        DispatchStrategy greedy = registry.get(StrategyNames.GREEDY);

        long coldMs = timeMs(() -> greedy.plan(data.orders(), data.vehicles()));
        long warmMs = Long.MAX_VALUE;
        for (int i = 1; i < WARM_UP_ITERATIONS; i++) {
            warmMs = Math.min(warmMs, timeMs(() -> greedy.plan(data.orders(), data.vehicles())));
        }

        // The first upsert pays for Hibernate/JDBC class loading and JIT; keep it out of the scenarios.
        clearTables();
        GeneratedDataset upsertData = generator.generate(WARM_UP_UPSERT_ORDERS, WARM_UP_UPSERT_VEHICLES, SEED);
        long start = System.nanoTime();
        vehicleService.upsertVehicles(upsertData.vehicles());
        orderService.upsertOrders(upsertData.orders());
        long coldUpsertMs = elapsedMs(start);
        clearTables();

        start = System.nanoTime();
        boolean nativeLoaded = nativeLoader.ensureLoaded();
        long nativeLoadMs = elapsedMs(start);
        report.setWarmUp(new WarmUpResult(WARM_UP_ORDERS, WARM_UP_VEHICLES, WARM_UP_ITERATIONS, coldMs, warmMs,
                WARM_UP_UPSERT_ORDERS, WARM_UP_UPSERT_VEHICLES, coldUpsertMs, nativeLoadMs));
        log.info("Warm-up: greedy {} x {} cold {} ms, warm {} ms; cold upsert {} ms; OR-Tools natives loaded in {} ms",
                WARM_UP_ORDERS, WARM_UP_VEHICLES, coldMs, warmMs, coldUpsertMs, nativeLoadMs);

        assertThat(nativeLoaded).as("OR-Tools native libraries must load on this platform").isTrue();
    }

    @Test
    @Order(2)
    void greedy1000Orders50Vehicles() {
        runAndAssert(GREEDY_1K);
    }

    @Test
    @Order(3)
    void greedy10000Orders200Vehicles() {
        runAndAssert(GREEDY_10K);
    }

    @Test
    @Order(4)
    void greedy50000Orders500Vehicles() {
        runAndAssert(GREEDY_50K);
    }

    @Test
    @Order(5)
    void orTools200Orders20Vehicles() {
        runOrToolsWithGreedyComparison(ORTOOLS_SMALL_ORDERS, ORTOOLS_SMALL_VEHICLES);
    }

    /** 1,000 orders is exactly the default cap: the cap rejects only more than max-orders, so no fallback. */
    @Test
    @Order(6)
    void orTools1000Orders50VehiclesAtTheCap() {
        assertThat(ORTOOLS_CAP_ORDERS).as("scenario sits exactly at the configured cap")
                .isEqualTo(properties.ortools().maxOrders());
        orToolsAtCap = runOrToolsWithGreedyComparison(ORTOOLS_CAP_ORDERS, ORTOOLS_CAP_VEHICLES);
    }

    /**
     * Same 1,000 x 50 data with longer OR-Tools time limits (a strategy instance built with the
     * production constructor and only the time limit changed), to show how far the 3 s result is
     * from converged. Asserts the contract, not the priority outcome: see the report.
     */
    @Test
    @Order(7)
    void orToolsTimeLimitSensitivityAtTheCap() {
        GeneratedDataset data = generator.generate(ORTOOLS_CAP_ORDERS, ORTOOLS_CAP_VEHICLES, SEED);
        if (orToolsAtCap != null) {
            report.addTimeLimitRow(timeLimitRow(properties.ortools().timeLimit(), orToolsAtCap.result(),
                    orToolsAtCap.measurement().planMs()));
        }
        DispatchStrategy greedy = registry.get(StrategyNames.GREEDY);
        for (Duration timeLimit : SENSITIVITY_TIME_LIMITS) {
            DispatchProperties tuned = new DispatchProperties(properties.upsertChunkSize(), properties.greedy(),
                    new DispatchProperties.OrTools(timeLimit, properties.ortools().maxOrders()));
            DispatchStrategy orTools = new OrToolsDispatchStrategy(distanceCalculator, tuned, greedy, nativeLoader);

            long start = System.nanoTime();
            DispatchResult result = orTools.plan(data.orders(), data.vehicles());
            long planMs = elapsedMs(start);
            report.addTimeLimitRow(timeLimitRow(timeLimit, result, planMs));
            log.info("OR-Tools {} x {} with time limit {}: assigned {} {}, {} km, {} ms", ORTOOLS_CAP_ORDERS,
                    ORTOOLS_CAP_VEHICLES, timeLimit, result.assignedCount(), PlanInvariants.assignedByPriority(result),
                    km(result.totalDistanceKm()), planMs);

            PlanInvariants.assertContract(result, data.orders(), data.vehicles(), StrategyNames.ORTOOLS,
                    distanceCalculator);
            assertThat(planMs).isLessThan(timeLimit.toMillis() + ORTOOLS_OVERHEAD_MS);
        }
    }

    private static TimeLimitRow timeLimitRow(Duration timeLimit, DispatchResult result, long planMs) {
        return new TimeLimitRow(timeLimit.toMillis(), result.strategy(), PlanInvariants.assignedByPriority(result),
                PlanInvariants.unassignedByPriority(result), result.totalDistanceKm(), planMs);
    }

    private Measured runOrToolsWithGreedyComparison(int orders, int vehicles) {
        Scenario scenario = new Scenario(StrategyNames.ORTOOLS, orders, vehicles, ORTOOLS_UPSERT_LIMIT_MS,
                properties.ortools().timeLimit().toMillis() + ORTOOLS_OVERHEAD_MS);
        Measured orTools = runAndAssert(scenario);

        // Greedy on exactly the same entities, for the distance comparison.
        DispatchStrategy greedy = registry.get(StrategyNames.GREEDY);
        long start = System.nanoTime();
        DispatchResult greedyResult = greedy.plan(orTools.orders(), orTools.vehicles());
        long greedyMs = elapsedMs(start);

        report.addComparison(new ComparisonRow(orders, vehicles,
                orTools.result().totalDistanceKm(), orTools.result().assignedCount(),
                PlanInvariants.assignedByPriority(orTools.result()), orTools.measurement().planMs(),
                greedyResult.totalDistanceKm(), greedyResult.assignedCount(),
                PlanInvariants.assignedByPriority(greedyResult), greedyMs));
        log.info("Comparison {} x {}: ortools {} km / {} assigned, greedy {} km / {} assigned",
                orders, vehicles, km(orTools.result().totalDistanceKm()), orTools.result().assignedCount(),
                km(greedyResult.totalDistanceKm()), greedyResult.assignedCount());

        PlanInvariants.assertContract(greedyResult, orTools.orders(), orTools.vehicles(), StrategyNames.GREEDY,
                distanceCalculator);
        PlanInvariants.assertHighPriorityInvariant(greedyResult);
        return orTools;
    }

    /** Runs one scenario, records it in the report, then asserts invariants and thresholds. */
    private Measured runAndAssert(Scenario scenario) {
        clearTables();
        DispatchStrategy strategy = registry.get(scenario.strategy());

        UpsertResult vehicleUpsert;
        UpsertResult orderUpsert;
        long upsertVehiclesMs;
        long upsertOrdersMs;
        long planMs;
        long endToEndMs;
        DispatchResult result;
        DispatchPlanResponse response;
        List<DeliveryOrder> orders;
        List<Vehicle> vehicles;
        long totalWeight;
        long totalCapacity;
        HeapMonitor.HeapStats heap;
        try (HeapMonitor monitor = HeapMonitor.start()) {
            GeneratedDataset data = generator.generate(scenario.orders(), scenario.vehicles(), SEED);
            totalWeight = data.orders().stream().mapToLong(DeliveryOrder::getPackageWeight).sum();
            totalCapacity = data.vehicles().stream().mapToLong(Vehicle::getCapacity).sum();

            long start = System.nanoTime();
            vehicleUpsert = vehicleService.upsertVehicles(data.vehicles());
            upsertVehiclesMs = elapsedMs(start);
            start = System.nanoTime();
            orderUpsert = orderService.upsertOrders(data.orders());
            upsertOrdersMs = elapsedMs(start);

            vehicles = vehicleService.findAllVehicles();
            orders = orderService.findAllOrders();

            start = System.nanoTime();
            result = strategy.plan(orders, vehicles);
            planMs = elapsedMs(start);

            start = System.nanoTime();
            response = dispatchService.generatePlan(scenario.strategy());
            endToEndMs = elapsedMs(start);
            heap = monitor.stop();
        }

        ScenarioResult measurement = new ScenarioResult(result.strategy(), scenario.orders(), scenario.vehicles(),
                totalWeight, totalCapacity, upsertVehiclesMs, upsertOrdersMs, planMs, endToEndMs,
                result.totalDistanceKm(), result.assignedCount(), result.unassignedOrders().size(),
                PlanInvariants.assignedByPriority(result), PlanInvariants.unassignedByPriority(result), heap);
        report.addScenario(measurement);
        log.info("Scenario {} {} x {}: upsert {} ms (vehicles {} + orders {}), plan {} ms, end-to-end {} ms, "
                        + "{} km, assigned {}, unassigned {}, peak heap {} MB",
                result.strategy(), scenario.orders(), scenario.vehicles(), measurement.upsertMs(), upsertVehiclesMs,
                upsertOrdersMs, planMs, endToEndMs, km(result.totalDistanceKm()), result.assignedCount(),
                result.unassignedOrders().size(), Math.round(heap.sampledPeakMb()));

        assertThat(vehicleUpsert.inserted()).as("vehicles inserted").isEqualTo(scenario.vehicles());
        assertThat(orderUpsert.inserted()).as("orders inserted").isEqualTo(scenario.orders());
        assertThat(vehicles).hasSize(scenario.vehicles());
        assertThat(orders).hasSize(scenario.orders());

        PlanInvariants.assertContract(result, orders, vehicles, scenario.strategy(), distanceCalculator);
        if (StrategyNames.GREEDY.equals(scenario.strategy())) {
            PlanInvariants.assertHighPriorityInvariant(result);
        }
        PlanInvariants.assertResponse(response, orders, vehicles, scenario.strategy());

        assertThat(measurement.upsertMs()).as("upsert ms of %s", scenario).isLessThan(scenario.upsertLimitMs());
        assertThat(planMs).as("plan ms of %s", scenario).isLessThan(scenario.planLimitMs());
        assertThat(endToEndMs).as("end-to-end ms of %s", scenario)
                .isLessThan(scenario.planLimitMs() + END_TO_END_EXTRA_MS);
        return new Measured(measurement, result, orders, vehicles);
    }

    private Map<String, String> settings() {
        DispatchProperties.Greedy greedy = properties.greedy();
        DispatchProperties.OrTools orTools = properties.ortools();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("Seed", Long.toString(SEED));
        values.put("Data", "TestDataGenerator: points within " + (int) TestDataGenerator.MAX_OFFSET_KM
                + " km of 30 Indian city centres; weight " + TestDataGenerator.MIN_PACKAGE_WEIGHT + "-"
                + TestDataGenerator.MAX_PACKAGE_WEIGHT + "; capacity " + TestDataGenerator.MIN_CAPACITY + "-"
                + TestDataGenerator.MAX_CAPACITY + "; priority " + TestDataGenerator.HIGH_PERCENT + "% HIGH / "
                + TestDataGenerator.MEDIUM_PERCENT + "% MEDIUM / rest LOW");
        values.put("Greedy 2-opt max passes per vehicle", Integer.toString(greedy.twoOptMaxIterations()));
        values.put("Greedy 2-opt time budget per vehicle", greedy.twoOptTimeBudget().toMillis() + " ms");
        values.put("OR-Tools time limit", orTools.timeLimit().toMillis() + " ms (greedy warm start + "
                + "GUIDED_LOCAL_SEARCH; PATH_CHEAPEST_ARC only without a seed)");
        values.put("OR-Tools max orders", orTools.maxOrders() + " (falls back to greedy only above this)");
        values.put("Upsert chunk size", Integer.toString(properties.upsertChunkSize()));
        values.put("Hibernate JDBC batch size",
                environment.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size", "n/a"));
        values.put("Warm-up", WARM_UP_ITERATIONS + " greedy plans of " + WARM_UP_ORDERS + " x " + WARM_UP_VEHICLES
                + " (in memory) and one upsert of " + WARM_UP_UPSERT_ORDERS + " x " + WARM_UP_UPSERT_VEHICLES
                + " before the timed scenarios");
        values.put("OR-Tools time-limit sensitivity", ORTOOLS_CAP_ORDERS + " x " + ORTOOLS_CAP_VEHICLES + " at "
                + SENSITIVITY_TIME_LIMITS.stream().map(d -> d.toSeconds() + " s").toList() + " besides the configured "
                + "limit");
        values.put("Thresholds: greedy plan", "1,000 x 50 < " + GREEDY_1K.planLimitMs() + " ms; 10,000 x 200 < "
                + GREEDY_10K.planLimitMs() + " ms; 50,000 x 500 < " + GREEDY_50K.planLimitMs() + " ms");
        values.put("Thresholds: upsert (vehicles + orders)", "1,000 < " + GREEDY_1K.upsertLimitMs() + " ms; 10,000 < "
                + GREEDY_10K.upsertLimitMs() + " ms; 50,000 < " + GREEDY_50K.upsertLimitMs() + " ms; OR-Tools "
                + "datasets < " + ORTOOLS_UPSERT_LIMIT_MS + " ms");
        values.put("Thresholds: OR-Tools plan", "time limit + " + ORTOOLS_OVERHEAD_MS + " ms");
        values.put("Thresholds: end-to-end generatePlan", "plan threshold + " + END_TO_END_EXTRA_MS + " ms");
        return values;
    }

    private String databaseDescription() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion() + " (" + meta.getURL() + ")";
        }
    }

    private void clearTables() {
        orderService.deleteAllOrders();
        vehicleService.deleteAllVehicles();
    }

    private static long timeMs(Supplier<?> action) {
        long start = System.nanoTime();
        action.get();
        return elapsedMs(start);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / NANOS_PER_MS;
    }

    private static String km(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** A scenario and its thresholds. */
    private record Scenario(String strategy, int orders, int vehicles, long upsertLimitMs, long planLimitMs) {
        @Override
        public String toString() {
            return strategy + " " + orders + " x " + vehicles;
        }
    }

    /** A recorded measurement plus the data it was computed on (reused for the comparison). */
    private record Measured(ScenarioResult measurement, DispatchResult result, List<DeliveryOrder> orders,
                            List<Vehicle> vehicles) {
    }
}
