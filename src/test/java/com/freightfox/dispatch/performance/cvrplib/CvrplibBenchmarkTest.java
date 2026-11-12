package com.freightfox.dispatch.performance.cvrplib;

import com.freightfox.dispatch.config.DispatchProperties;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.performance.PerformanceReport;
import com.freightfox.dispatch.performance.PlanInvariants;
import com.freightfox.dispatch.performance.cvrplib.CvrplibInstance.Node;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.DispatchStrategy;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.greedy.GreedyPriorityDispatchStrategy;
import com.freightfox.dispatch.strategy.ortools.OrToolsDispatchStrategy;
import com.freightfox.dispatch.strategy.ortools.OrToolsNativeLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 11.4: greedy and OR-Tools on three genuine CVRPLIB "X" instances, evaluated with the
 * CVRPLIB metric and compared with the best-known solutions. Plain JUnit (no Spring context): the
 * production strategy classes are built with their public constructors, a test-only Euclidean
 * {@link com.freightfox.dispatch.distance.DistanceCalculator} and the default tuning values.
 */
@Tag("performance")
class CvrplibBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(CvrplibBenchmarkTest.class);

    private static final String RESOURCE_DIR = "/cvrplib/";
    private static final String INSTANCE_SUFFIX = ".vrp";
    private static final String SOLUTION_SUFFIX = ".sol";
    private static final long NANOS_PER_MS = 1_000_000L;

    /** Same values as the application.yml defaults. */
    private static final int UPSERT_CHUNK_SIZE = 1_000;
    private static final int TWO_OPT_MAX_PASSES = 1_000;
    private static final Duration TWO_OPT_TIME_BUDGET = Duration.ofMillis(25);
    private static final Duration ORTOOLS_TIME_LIMIT = Duration.ofSeconds(3);
    private static final int ORTOOLS_MAX_ORDERS = 1_000;

    private static final long GREEDY_PLAN_LIMIT_MS = 2_000;
    private static final long ORTOOLS_OVERHEAD_MS = 10_000;

    private static final DispatchProperties PROPERTIES = new DispatchProperties(UPSERT_CHUNK_SIZE,
            new DispatchProperties.Greedy(TWO_OPT_MAX_PASSES, TWO_OPT_TIME_BUDGET),
            new DispatchProperties.OrTools(ORTOOLS_TIME_LIMIT, ORTOOLS_MAX_ORDERS));

    /** Shared and loaded in {@code @BeforeAll}, so the one-off native load is never inside a timed plan. */
    private static final OrToolsNativeLoader NATIVE_LOADER = new OrToolsNativeLoader();

    private final DispatchStrategy greedy = new GreedyPriorityDispatchStrategy(CvrplibAdapter.DISTANCE, PROPERTIES);
    private final DispatchStrategy orTools = new OrToolsDispatchStrategy(CvrplibAdapter.DISTANCE, PROPERTIES, greedy,
            NATIVE_LOADER);

    static Stream<String> instances() {
        return Stream.of("X-n101-k25", "X-n106-k14", "X-n110-k13");
    }

    @BeforeAll
    static void describeSettings() {
        long start = System.nanoTime();
        assertThat(NATIVE_LOADER.ensureLoaded()).as("OR-Tools native libraries load on this platform").isTrue();
        long nativeLoadMs = (System.nanoTime() - start) / NANOS_PER_MS;
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("Instances", String.join(", ", instances().toList()) + " (CVRPLIB set X, files in "
                + "src/test/resources/cvrplib)");
        settings.put("Distance", "EUC_2D: Euclidean, rounded to the nearest integer per arc");
        settings.put("Evaluation", "closed tours depot -> stops -> depot");
        settings.put("Fleet", "max(k, routes in the best-known solution), all at the depot, capacity Q");
        settings.put("Priorities", "all customers " + CvrplibAdapter.PRIORITY);
        settings.put("Greedy 2-opt", TWO_OPT_MAX_PASSES + " passes / " + TWO_OPT_TIME_BUDGET.toMillis()
                + " ms per vehicle");
        settings.put("OR-Tools time limit", ORTOOLS_TIME_LIMIT.toMillis() + " ms (PATH_CHEAPEST_ARC + "
                + "GUIDED_LOCAL_SEARCH)");
        settings.put("Thresholds", "greedy plan < " + GREEDY_PLAN_LIMIT_MS + " ms; OR-Tools plan < time limit + "
                + ORTOOLS_OVERHEAD_MS + " ms");
        settings.put("OR-Tools native load (before any timed plan)", nativeLoadMs + " ms");
        PerformanceReport.get().putSettings("CVRPLIB benchmark", settings);
    }

    @AfterAll
    static void writeReport() {
        PerformanceReport.get().write();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("instances")
    void benchmark(String name) throws IOException {
        CvrplibInstance instance = load(name + INSTANCE_SUFFIX, CvrplibInstance::parse);
        CvrplibSolution bks = load(name + SOLUTION_SUFFIX, CvrplibSolution::parse);
        CvrplibAdapter adapter = new CvrplibAdapter(instance);
        assertThat(instance.name()).isEqualTo(name);

        // Validates parser, node numbering and metric: the published BKS cost must be reproduced exactly.
        List<List<Node>> bksRoutes = adapter.routes(bks);
        assertBksIsFeasible(instance, bksRoutes);
        assertThat(adapter.closedCost(bksRoutes)).as("%s: re-evaluated best-known cost", name).isEqualTo(bks.cost());

        int fleet = Math.max(instance.k(), bks.routes().size());
        List<Vehicle> vehicles = adapter.vehicles(fleet);
        List<DeliveryOrder> orders = adapter.orders();

        greedy.plan(orders, vehicles); // JIT warm-up, not timed
        Run greedyRun = run(greedy, orders, vehicles);
        Run orToolsRun = run(orTools, orders, vehicles);

        for (Run run : List.of(greedyRun, orToolsRun)) {
            List<List<Node>> routes = adapter.routes(run.result());
            CvrplibRow row = new CvrplibRow(name, instance.customers().size(), instance.capacity(), instance.k(),
                    bks.routes().size(), fleet, bks.cost(), run.result().strategy(), run.result().assignedCount(),
                    routes.size(), adapter.closedCost(routes), adapter.openCost(routes), run.planMs());
            PerformanceReport.get().addCvrplib(row);
            log.info("CVRPLIB {} {}: served {}/{}, routes {}, cost {} (BKS {}, gap {}%), open {}, {} ms", name,
                    row.strategy(), row.served(), row.customers(), row.routesUsed(), row.closedCost(),
                    row.bestKnownCost(), String.format("%.1f", row.gapPercent()), row.openCost(), row.planMs());
        }

        PlanInvariants.assertContract(greedyRun.result(), orders, vehicles, StrategyNames.GREEDY,
                CvrplibAdapter.DISTANCE);
        PlanInvariants.assertContract(orToolsRun.result(), orders, vehicles, StrategyNames.ORTOOLS,
                CvrplibAdapter.DISTANCE);
        assertThat(greedyRun.planMs()).isLessThan(GREEDY_PLAN_LIMIT_MS);
        assertThat(orToolsRun.planMs()).isLessThan(ORTOOLS_TIME_LIMIT.toMillis() + ORTOOLS_OVERHEAD_MS);
    }

    private static void assertBksIsFeasible(CvrplibInstance instance, List<List<Node>> routes) {
        Set<Integer> visited = new HashSet<>();
        for (List<Node> route : routes) {
            assertThat(route).doesNotContainNull();
            assertThat(route.stream().mapToInt(Node::demand).sum()).isLessThanOrEqualTo(instance.capacity());
            route.forEach(node -> assertThat(visited.add(node.id())).isTrue());
        }
        assertThat(visited).hasSize(instance.customers().size());
    }

    private static Run run(DispatchStrategy strategy, List<DeliveryOrder> orders, List<Vehicle> vehicles) {
        long start = System.nanoTime();
        DispatchResult result = strategy.plan(orders, vehicles);
        return new Run(result, (System.nanoTime() - start) / NANOS_PER_MS);
    }

    private static <T> T load(String file, Parser<T> parser) throws IOException {
        try (InputStream in = CvrplibBenchmarkTest.class.getResourceAsStream(RESOURCE_DIR + file)) {
            assertThat(in).as("resource %s", file).isNotNull();
            return parser.parse(in);
        }
    }

    @FunctionalInterface
    private interface Parser<T> {
        T parse(InputStream in) throws IOException;
    }

    private record Run(DispatchResult result, long planMs) {
    }
}
