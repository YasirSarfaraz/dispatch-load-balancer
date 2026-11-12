package com.freightfox.dispatch.strategy.ortools;

import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Solves a {@link CvrpModel} with the OR-Tools routing library.
 *
 * <ul>
 *   <li>{@code RoutingIndexManager(numNodes, numVehicles, starts[], ends[])}: each vehicle starts
 *       at its own node and all end at the shared dummy end node (open routes).</li>
 *   <li>Arc cost = distance matrix (transit callback, same evaluator for every vehicle).</li>
 *   <li>Capacity dimension: demand = packageWeight, no slack, per-vehicle capacity, cumul starts at 0.</li>
 *   <li>Every order node is an optional visit: a single-node disjunction with the
 *       {@link DropPenalties priority penalty}.</li>
 *   <li>Search: GUIDED_LOCAL_SEARCH until the time limit, starting from
 *       <ul>
 *         <li>the <b>seed routes</b> (warm start) when given: they are loaded with
 *             {@code readAssignmentFromRoutes} and the search runs with
 *             {@code solveFromAssignmentWithParameters}. Local search only ever returns a solution
 *             whose objective is at most the seed's, so with lexicographic drop penalties the result
 *             never drops more HIGH (then MEDIUM, then LOW) orders than the seed;</li>
 *         <li>a PATH_CHEAPEST_ARC first solution otherwise (no seed, an empty seed, or a seed that
 *             OR-Tools rejects as infeasible).</li>
 *       </ul></li>
 * </ul>
 *
 * <p><b>Index semantics</b> (verified by {@code OrToolsCvrpSolverTest}): in OR-Tools 9.15 the
 * inner arrays passed to {@code readAssignmentFromRoutes} are routing <em>variable indices</em>
 * ({@code manager.nodeToIndex(node)}), not node numbers, and exclude the start and end indices.
 * With one start node per vehicle the two differ (order node {@code V + i} is index {@code i}), so
 * the seed is converted by {@link #toRoutingIndices}.
 *
 * <p>Requires the native libraries to be loaded ({@link OrToolsNativeLoader}). Native objects are
 * released explicitly after the solution has been copied into plain Java objects.
 */
final class OrToolsCvrpSolver implements CvrpSolver {

    static final String CAPACITY_DIMENSION = "Capacity";

    private static final Logger log = LoggerFactory.getLogger(OrToolsCvrpSolver.class);

    private static final long NO_SLACK = 0;
    private static final boolean FIX_START_CUMUL_TO_ZERO = true;
    /** Skip seed indices of nodes that are inactive in the model instead of rejecting the whole seed. */
    private static final boolean IGNORE_INACTIVE_INDICES = true;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    @Override
    public Optional<CvrpSolution> solve(CvrpModel model, List<List<Integer>> seedRoutes, Duration timeLimit) {
        RoutingIndexManager manager = newManager(model);
        RoutingModel routing = new RoutingModel(manager);
        try {
            int[] nodeOfIndex = nodeOfIndex(manager);
            configure(model, manager, routing, nodeOfIndex);
            RoutingSearchParameters parameters = searchParameters(timeLimit);
            // Close with OUR parameters (GLS, time limit): readAssignmentFromRoutes would otherwise
            // close the model with the default parameters.
            routing.closeModelWithParameters(parameters);

            Assignment seed = readSeed(model, manager, routing, seedRoutes);
            Assignment solution = seed != null
                    ? routing.solveFromAssignmentWithParameters(seed, parameters)
                    : routing.solveWithParameters(parameters);
            if (solution == null) {
                return Optional.empty();
            }
            return Optional.of(new CvrpSolution(extractRoutes(model, routing, solution, nodeOfIndex),
                    solution.objectiveValue(), routing.status().name(), seed != null));
        } finally {
            routing.delete();
            manager.delete();
        }
    }

    /** One start node per vehicle, all ending at the shared dummy end node. */
    static RoutingIndexManager newManager(CvrpModel model) {
        return new RoutingIndexManager(model.numNodes(), model.numVehicles(), model.startNodes(), model.endNodes());
    }

    /** Arc costs, capacity dimension and one drop-penalty disjunction per order. */
    static void configure(CvrpModel model, RoutingIndexManager manager, RoutingModel routing, int[] nodeOfIndex) {
        int transit = routing.registerTransitCallback(
                (from, to) -> model.cost(nodeOfIndex[(int) from], nodeOfIndex[(int) to]));
        routing.setArcCostEvaluatorOfAllVehicles(transit);

        int demand = routing.registerUnaryTransitCallback(index -> model.demand(nodeOfIndex[(int) index]));
        routing.addDimensionWithVehicleCapacity(demand, NO_SLACK, model.capacities(),
                FIX_START_CUMUL_TO_ZERO, CAPACITY_DIMENSION);

        DropPenalties penalties = model.penalties();
        for (int i = 0; i < model.numOrders(); i++) {
            long index = manager.nodeToIndex(model.orderNode(i));
            routing.addDisjunction(new long[] {index}, penalties.forPriority(model.orders().get(i).getPriority()));
        }
    }

    /**
     * Loads the seed as an OR-Tools assignment (the model must be closed).
     *
     * @return the seed assignment, or {@code null} if there is no usable seed (empty, wrong number
     *         of routes, or rejected by OR-Tools as infeasible); the caller then uses PATH_CHEAPEST_ARC
     */
    private static Assignment readSeed(CvrpModel model, RoutingIndexManager manager, RoutingModel routing,
                                       List<List<Integer>> seedRoutes) {
        int seedStops = seedRoutes.stream().mapToInt(List::size).sum();
        if (seedStops == 0 || seedRoutes.size() != model.numVehicles()) {
            log.info("OR-Tools: no warm-start seed ({} routes, {} stops for {} vehicles); building the first "
                    + "solution with PATH_CHEAPEST_ARC", seedRoutes.size(), seedStops, model.numVehicles());
            return null;
        }
        Assignment seed = routing.readAssignmentFromRoutes(toRoutingIndices(manager, model, seedRoutes),
                IGNORE_INACTIVE_INDICES);
        if (seed == null) {
            log.warn("OR-Tools rejected the warm-start seed ({} stops) as infeasible; building the first "
                    + "solution with PATH_CHEAPEST_ARC", seedStops);
        }
        return seed;
    }

    /**
     * Converts seed routes (model order indices) into the routing variable indices expected by
     * {@code readAssignmentFromRoutes}: order {@code i} is node {@code V + i}, whose variable index
     * is {@code manager.nodeToIndex(V + i)}. Start and end indices are not included.
     */
    static long[][] toRoutingIndices(RoutingIndexManager manager, CvrpModel model, List<List<Integer>> seedRoutes) {
        long[][] routes = new long[seedRoutes.size()][];
        for (int v = 0; v < routes.length; v++) {
            List<Integer> route = seedRoutes.get(v);
            routes[v] = new long[route.size()];
            for (int s = 0; s < route.size(); s++) {
                routes[v][s] = manager.nodeToIndex(model.orderNode(route.get(s)));
            }
        }
        return routes;
    }

    /** Index -> node lookup table, so callbacks do not cross JNI for every arc evaluation. */
    static int[] nodeOfIndex(RoutingIndexManager manager) {
        int[] nodes = new int[manager.getNumberOfIndices()];
        for (int index = 0; index < nodes.length; index++) {
            nodes[index] = manager.indexToNode(index);
        }
        return nodes;
    }

    /**
     * PATH_CHEAPEST_ARC (used only when the search does not start from a seed) + GUIDED_LOCAL_SEARCH,
     * with the configured time limit.
     */
    static RoutingSearchParameters searchParameters(Duration timeLimit) {
        com.google.protobuf.Duration limit = com.google.protobuf.Duration.newBuilder()
                .setSeconds(timeLimit.getSeconds())
                .setNanos((int) (timeLimit.toNanos() % NANOS_PER_SECOND))
                .build();
        return main.defaultRoutingSearchParameters().toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
                .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(limit)
                .build();
    }

    /** Walks each vehicle's chain start -> ... -> end and records the order nodes in between. */
    static List<List<Integer>> extractRoutes(CvrpModel model, RoutingModel routing, Assignment solution,
                                             int[] nodeOfIndex) {
        List<List<Integer>> routes = new ArrayList<>(model.numVehicles());
        for (int v = 0; v < model.numVehicles(); v++) {
            List<Integer> stops = new ArrayList<>();
            long index = solution.value(routing.nextVar(routing.start(v)));
            while (!routing.isEnd(index)) {
                int node = nodeOfIndex[(int) index];
                if (model.isOrderNode(node)) {
                    stops.add(model.orderIndex(node));
                }
                index = solution.value(routing.nextVar(index));
            }
            routes.add(stops);
        }
        return routes;
    }
}
