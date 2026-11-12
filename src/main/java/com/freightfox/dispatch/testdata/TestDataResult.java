package com.freightfox.dispatch.testdata;

/**
 * Outcome of {@link TestDataService#replaceWithGeneratedData(int, int, long)}.
 *
 * @param ordersGenerated   orders generated and stored
 * @param vehiclesGenerated vehicles generated and stored
 * @param seed              seed that was used
 * @param elapsedMs         wall-clock time for generate + clear + upsert, in milliseconds
 */
public record TestDataResult(int ordersGenerated, int vehiclesGenerated, long seed, long elapsedMs) {
}
