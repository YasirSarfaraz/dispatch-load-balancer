package com.freightfox.dispatch.performance;

/**
 * JIT warm-up run before any timed scenario.
 *
 * @param orders        orders in the in-memory greedy warm-up dataset
 * @param vehicles      vehicles in the greedy warm-up dataset
 * @param iterations    number of greedy plans run
 * @param coldMs        first greedy plan in this JVM (interpreted / C1 code)
 * @param warmMs        fastest of the remaining greedy plans (JIT-compiled code)
 * @param upsertOrders  orders in the upsert warm-up
 * @param upsertVehicles vehicles in the upsert warm-up
 * @param coldUpsertMs  first upsert (vehicles + orders) in this JVM, then the tables are cleared
 * @param nativeLoadMs  OR-Tools native library load time (0 if already loaded in this JVM)
 */
public record WarmUpResult(int orders, int vehicles, int iterations, long coldMs, long warmMs,
                           int upsertOrders, int upsertVehicles, long coldUpsertMs, long nativeLoadMs) {
}
