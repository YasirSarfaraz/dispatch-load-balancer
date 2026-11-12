package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of {@link OrToolsCvrpSolver}, including the index semantics of OR-Tools'
 * {@code readAssignmentFromRoutes} that the warm start relies on.
 */
class OrToolsCvrpSolverTest {

    private static final double ORIGIN_LAT = 28.6139;
    private static final double ORIGIN_LON = 77.2090;
    private static final Duration SHORT_LIMIT = Duration.ofMillis(300);
    private static final long DROP_PENALTY = 1_000;

    private final CvrpModelBuilder builder = new CvrpModelBuilder(new TestHaversine());
    private final OrToolsCvrpSolver solver = new OrToolsCvrpSolver();

    @BeforeAll
    static void loadNatives() {
        assertThat(new OrToolsNativeLoader().ensureLoaded()).isTrue();
    }

    // ---------------------------------------------------------------- OR-Tools API semantics

    /**
     * OR-Tools 9.15: the routes passed to {@code readAssignmentFromRoutes} are routing VARIABLE
     * indices (manager.nodeToIndex), not node numbers. The layout puts an end-only node first, so
     * customer nodes and their indices differ and the two interpretations can be told apart.
     */
    @Test
    void readAssignmentFromRoutesTakesVariableIndicesNotNodeNumbers() {
        // node 0 = shared end (end only), nodes 1-2 = vehicle starts, nodes 3-5 = customers
        RoutingIndexManager manager = new RoutingIndexManager(6, 2, new int[] {1, 2}, new int[] {0, 0});
        RoutingModel routing = new RoutingModel(manager);
        try {
            for (int node = 3; node <= 5; node++) {
                routing.addDisjunction(new long[] {manager.nodeToIndex(node)}, DROP_PENALTY);
            }
            routing.closeModel();
            assertThat(manager.nodeToIndex(3)).as("customer node 3 is not variable index 3").isEqualTo(2);

            long[][] byIndex = {{manager.nodeToIndex(5), manager.nodeToIndex(3)}, {manager.nodeToIndex(4)}};
            Assignment fromIndices = routing.readAssignmentFromRoutes(byIndex, true);
            assertThat(fromIndices).isNotNull();
            assertThat(routeNodes(manager, routing, fromIndices)).containsExactly(List.of(5, 3), List.of(4));

            // The same routes given as raw node numbers are read as indices: 5 is vehicle 0's END
            // index (nodes 1-5 are indices 0-4, the two ends are 5 and 6), so OR-Tools rejects them.
            long[][] byNode = {{5, 3}, {4}};
            assertThat(manager.getNumberOfIndices()).isEqualTo(7);
            assertThat(routing.isEnd(5)).isTrue();
            assertThat(routing.readAssignmentFromRoutes(byNode, true)).isNull();
        } finally {
            routing.delete();
            manager.delete();
        }
    }

    /** The production conversion round-trips: seed -> variable indices -> assignment -> same routes. */
    @Test
    void productionSeedConversionRoundTripsThroughReadAssignmentFromRoutes() {
        CvrpModel model = twoVehicleModel(100);
        RoutingIndexManager manager = OrToolsCvrpSolver.newManager(model);
        RoutingModel routing = new RoutingModel(manager);
        try {
            int[] nodeOfIndex = OrToolsCvrpSolver.nodeOfIndex(manager);
            OrToolsCvrpSolver.configure(model, manager, routing, nodeOfIndex);
            routing.closeModelWithParameters(OrToolsCvrpSolver.searchParameters(SHORT_LIMIT));
            List<List<Integer>> seed = List.of(List.of(2, 0), List.of(1));

            long[][] indices = OrToolsCvrpSolver.toRoutingIndices(manager, model, seed);
            Assignment assignment = routing.readAssignmentFromRoutes(indices, true);

            assertThat(indices[0]).containsExactly(manager.nodeToIndex(model.orderNode(2)),
                    manager.nodeToIndex(model.orderNode(0)));
            assertThat(assignment).isNotNull();
            assertThat(OrToolsCvrpSolver.extractRoutes(model, routing, assignment, nodeOfIndex))
                    .isEqualTo(seed);
        } finally {
            routing.delete();
            manager.delete();
        }
    }

    // ---------------------------------------------------------------- solve

    @Test
    void seededSearchIsWarmStartedAndImprovesTheSeed() {
        // seed visits C (3 km), A (1 km), B (2 km); the optimum is A, B, C
        CvrpSolution solution = solver.solve(lineModel(), List.of(List.of(2, 0, 1)), SHORT_LIMIT).orElseThrow();

        assertThat(solution.warmStarted()).isTrue();
        assertThat(solution.routes()).containsExactly(List.of(0, 1, 2));
    }

    @Test
    void emptySeedUsesPathCheapestArc() {
        CvrpSolution solution = solver.solve(lineModel(), List.of(List.of()), SHORT_LIMIT).orElseThrow();

        assertThat(solution.warmStarted()).isFalse();
        assertThat(solution.routes()).containsExactly(List.of(0, 1, 2));
    }

    @Test
    void seedWithTheWrongNumberOfRoutesIsIgnored() {
        CvrpSolution solution = solver.solve(lineModel(), List.of(List.of(0), List.of(1, 2)), SHORT_LIMIT)
                .orElseThrow();

        assertThat(solution.warmStarted()).isFalse();
        assertThat(solution.routes()).containsExactly(List.of(0, 1, 2));
    }

    @Test
    void infeasibleSeedIsRejectedAndTheSolverStartsFromScratch() {
        // 3 x 10 kg on a 15 kg vehicle: OR-Tools rejects the seed and builds its own first solution
        CvrpModel model = twoVehicleModel(15);

        CvrpSolution solution = solver.solve(model, List.of(List.of(0, 1, 2), List.of()), SHORT_LIMIT).orElseThrow();

        assertThat(solution.warmStarted()).isFalse();
        assertThat(solution.routes()).allSatisfy(route -> assertThat(route).hasSizeLessThanOrEqualTo(1));
    }

    // ---------------------------------------------------------------- helpers

    /** Visited nodes per vehicle, read back from an assignment via nextVar. */
    private static List<List<Integer>> routeNodes(RoutingIndexManager manager, RoutingModel routing,
                                                  Assignment assignment) {
        List<List<Integer>> routes = new ArrayList<>();
        for (int v = 0; v < routing.vehicles(); v++) {
            List<Integer> nodes = new ArrayList<>();
            long index = assignment.value(routing.nextVar(routing.start(v)));
            while (!routing.isEnd(index)) {
                nodes.add(manager.indexToNode(index));
                index = assignment.value(routing.nextVar(index));
            }
            routes.add(nodes);
        }
        return routes;
    }

    /** Two vehicles, three 10 kg HIGH orders north of the first vehicle. */
    private CvrpModel twoVehicleModel(int capacity) {
        List<Vehicle> vehicles = List.of(vehicle("V1", capacity, 0), vehicle("V2", capacity, 0.5));
        return builder.build(List.of(order("A", 1), order("B", 2), order("C", 3)), vehicles);
    }

    /** One vehicle and three orders north of it at 1, 2 and 3 km: the optimum visits A, B, C. */
    private CvrpModel lineModel() {
        return builder.build(List.of(order("A", 1), order("B", 2), order("C", 3)), List.of(vehicle("V1", 100, 0)));
    }

    private static Vehicle vehicle(String id, int capacity, double dLon) {
        return new Vehicle(id, capacity, ORIGIN_LAT, ORIGIN_LON + dLon, "Depot " + id);
    }

    private static DeliveryOrder order(String id, double kmNorth) {
        return new DeliveryOrder(id, ORIGIN_LAT + kmNorth / 111.0, ORIGIN_LON, "Address " + id, 10, Priority.HIGH);
    }
}
