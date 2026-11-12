package com.freightfox.dispatch.performance.cvrplib;

/**
 * One strategy's result on one CVRPLIB instance, evaluated with the CVRPLIB metric.
 *
 * @param instance      instance name, e.g. X-n101-k25
 * @param customers     number of customers (DIMENSION - 1)
 * @param capacity      vehicle capacity Q
 * @param k             k from the instance name (minimum number of routes)
 * @param bksRoutes     routes in the best-known solution file
 * @param fleet         vehicles given to our strategies
 * @param bestKnownCost best-known solution cost from the .sol file
 * @param strategy      strategy that actually ran
 * @param served        customers placed on a route
 * @param routesUsed    non-empty routes
 * @param closedCost    CVRPLIB cost of our routes: depot -> stops -> depot, rounded Euclidean per arc
 * @param openCost      same without the return legs (what our production model minimises)
 * @param planMs        plan wall time
 */
public record CvrplibRow(
        String instance,
        int customers,
        int capacity,
        int k,
        int bksRoutes,
        int fleet,
        long bestKnownCost,
        String strategy,
        int served,
        int routesUsed,
        long closedCost,
        long openCost,
        long planMs) {

    /** Gap of {@link #closedCost} from the best-known cost, in percent. */
    public double gapPercent() {
        return 100.0 * (closedCost - bestKnownCost) / bestKnownCost;
    }

    /** True if every customer is on a route (only then is the gap a fair comparison). */
    public boolean allServed() {
        return served == customers;
    }
}
