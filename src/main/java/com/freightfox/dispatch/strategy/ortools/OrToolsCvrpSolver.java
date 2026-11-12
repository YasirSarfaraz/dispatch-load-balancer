package com.freightfox.dispatch.strategy.ortools;

import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;

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
 *   <li>Search: PATH_CHEAPEST_ARC first solution, then GUIDED_LOCAL_SEARCH until the time limit.</li>
 * </ul>
 *
 * <p>Requires the native libraries to be loaded ({@link OrToolsNativeLoader}). Native objects are
 * released explicitly after the solution has been copied into plain Java objects.
 */
final class OrToolsCvrpSolver implements CvrpSolver {

    static final String CAPACITY_DIMENSION = "Capacity";

    private static final long NO_SLACK = 0;
    private static final boolean FIX_START_CUMUL_TO_ZERO = true;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    @Override
    public Optional<CvrpSolution> solve(CvrpModel model, Duration timeLimit) {
        RoutingIndexManager manager = new RoutingIndexManager(
                model.numNodes(), model.numVehicles(), model.startNodes(), model.endNodes());
        RoutingModel routing = new RoutingModel(manager);
        try {
            int[] nodeOfIndex = nodeOfIndex(manager);

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

            Assignment solution = routing.solveWithParameters(searchParameters(timeLimit));
            if (solution == null) {
                return Optional.empty();
            }
            return Optional.of(new CvrpSolution(extractRoutes(model, routing, solution, nodeOfIndex),
                    solution.objectiveValue(), routing.status().name()));
        } finally {
            routing.delete();
            manager.delete();
        }
    }

    /** Index -> node lookup table, so callbacks do not cross JNI for every arc evaluation. */
    private static int[] nodeOfIndex(RoutingIndexManager manager) {
        int[] nodes = new int[manager.getNumberOfIndices()];
        for (int index = 0; index < nodes.length; index++) {
            nodes[index] = manager.indexToNode(index);
        }
        return nodes;
    }

    private static RoutingSearchParameters searchParameters(Duration timeLimit) {
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
    private static List<List<Integer>> extractRoutes(CvrpModel model, RoutingModel routing, Assignment solution,
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
