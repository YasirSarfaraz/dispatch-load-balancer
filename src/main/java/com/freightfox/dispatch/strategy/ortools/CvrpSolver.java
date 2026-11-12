package com.freightfox.dispatch.strategy.ortools;

import java.time.Duration;
import java.util.Optional;

/**
 * Solves a {@link CvrpModel}. The production implementation is {@link OrToolsCvrpSolver}; the
 * interface is the seam that lets tests exercise the "no solution" and "solver crashed" fallbacks.
 */
@FunctionalInterface
interface CvrpSolver {

    /**
     * @return the best solution found within {@code timeLimit}, or empty if none was found
     */
    Optional<CvrpSolution> solve(CvrpModel model, Duration timeLimit);
}
