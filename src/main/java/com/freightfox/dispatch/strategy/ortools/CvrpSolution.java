package com.freightfox.dispatch.strategy.ortools;

import java.util.List;

/**
 * A solver-independent CVRP solution.
 *
 * @param routes      for each vehicle (model order), the indices into {@link CvrpModel#orders()} in
 *                    visiting order; orders that appear in no route were dropped
 * @param objective   solver objective (arc cost + drop penalties), for logging
 * @param status      solver status name, for logging
 * @param warmStarted {@code true} if the search started from the seed routes, {@code false} if the
 *                    solver built its own first solution (no seed, or the seed was rejected)
 */
record CvrpSolution(List<List<Integer>> routes, long objective, String status, boolean warmStarted) {

    CvrpSolution {
        routes = routes.stream().map(List::copyOf).toList();
    }
}
