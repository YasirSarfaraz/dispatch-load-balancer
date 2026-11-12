package com.freightfox.dispatch.strategy.ortools;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Solves a {@link CvrpModel}. The production implementation is {@link OrToolsCvrpSolver}; the
 * interface is the seam that lets tests exercise the warm start, the "no solution" and "solver
 * crashed" fallbacks and the priority guard of {@link OrToolsDispatchStrategy}.
 */
@FunctionalInterface
interface CvrpSolver {

    /**
     * @param model      the instance to solve
     * @param seedRoutes warm-start solution: for each vehicle (model order), indices into
     *                   {@link CvrpModel#orders()} in visiting order (same shape as
     *                   {@link CvrpSolution#routes()}). An empty list, or routes without any stop,
     *                   means "no seed": the solver then builds its own first solution.
     * @param timeLimit  search time limit
     * @return the best solution found within {@code timeLimit}, or empty if none was found
     */
    Optional<CvrpSolution> solve(CvrpModel model, List<List<Integer>> seedRoutes, Duration timeLimit);
}
