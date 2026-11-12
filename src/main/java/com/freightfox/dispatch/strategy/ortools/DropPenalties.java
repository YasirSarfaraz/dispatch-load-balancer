package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.entity.Priority;

/**
 * Disjunction (drop) penalties per priority, plus the distance unit the arc costs are expressed in.
 *
 * <p>The OR-Tools objective is {@code totalArcCost + sum(penalty of every dropped order)}. The
 * penalties are derived from the data so that the objective is <em>lexicographic</em>:
 * <ol>
 *   <li>first minimise the number of dropped HIGH orders,</li>
 *   <li>then the number of dropped MEDIUM orders,</li>
 *   <li>then the number of dropped LOW orders,</li>
 *   <li>and only then the total distance.</li>
 * </ol>
 *
 * <p>Let {@code maxArc} be the largest matrix entry and {@code n = orderNodes + vehicles}. Every
 * visited node has exactly one outgoing arc (the arc into the dummy end costs 0), so
 * {@code routeBound = maxArc * n} is an upper bound on the total distance of ANY solution, and
 * therefore on the distance difference between any two solutions. With {@code nL}, {@code nM}
 * the number of LOW and MEDIUM orders in the model:
 * <pre>
 *   LOW    = routeBound + 1
 *            -> serving one more LOW order beats any possible distance saving
 *   MEDIUM = LOW * nL + routeBound + 1
 *            -> dropping one MEDIUM costs more than dropping every LOW order plus any distance
 *   HIGH   = MEDIUM * nM + LOW * nL + routeBound + 1
 *            -> dropping one HIGH costs more than dropping every MEDIUM and LOW order plus any distance
 * </pre>
 * Proof for HIGH (the others are identical): if solution A drops fewer HIGH orders than B, then
 * {@code obj(B) - obj(A) >= HIGH - (nM * MEDIUM + nL * LOW + routeBound) = 1 > 0}. So an optimal
 * solution never drops a HIGH order that could have been served, never trades a MEDIUM for LOW
 * orders, and never drops a LOW order just to save distance. (The solver is a heuristic, so this
 * is guaranteed for the optimum; in practice local search finds such a solution reliably.)
 *
 * <p><b>Overflow.</b> OR-Tools costs are int64 and saturate. The worst objective is
 * {@code nH * HIGH + nM * MEDIUM + nL * LOW + routeBound}, which grows like
 * {@code routeBound * nL * nM * nH}. All arithmetic uses {@link Math#multiplyExact} /
 * {@link Math#addExact}; if the worst objective does not fit in {@link #OBJECTIVE_BUDGET}, the
 * arc cost unit is coarsened (1 m, 10 m, 100 m, ...) until it fits. This only affects the
 * solver's internal cost resolution; reported distances are always recomputed in km from the
 * real coordinates. With the default cap of 1,000 orders the unit stays 1 m for realistic data.
 *
 * @param costUnitMetres metres represented by one unit of arc cost (1 = whole metres)
 * @param low            penalty for dropping a LOW order
 * @param medium         penalty for dropping a MEDIUM order
 * @param high           penalty for dropping a HIGH order
 */
public record DropPenalties(long costUnitMetres, long low, long medium, long high) {

    /** Largest allowed worst-case objective; half of int64 leaves headroom for solver internals. */
    static final long OBJECTIVE_BUDGET = Long.MAX_VALUE / 2;

    /** Cost-unit coarsening factor tried when the penalties would overflow. */
    private static final long UNIT_STEP = 10;

    /** Coarsest unit tried (1,000 km); beyond this the model is too large and OR-Tools is skipped. */
    private static final long MAX_COST_UNIT_METRES = 1_000_000;

    /**
     * Computes penalties for a model.
     *
     * @param maxArcMetres  largest distance matrix entry in metres
     * @param orderNodes    number of order nodes in the model
     * @param vehicles      number of vehicles
     * @param highCount     HIGH orders in the model
     * @param mediumCount   MEDIUM orders in the model
     * @param lowCount      LOW orders in the model
     * @throws IllegalStateException if no cost unit up to 1,000 km keeps the objective in int64
     */
    public static DropPenalties compute(long maxArcMetres, int orderNodes, int vehicles,
                                        int highCount, int mediumCount, int lowCount) {
        if (maxArcMetres < 0 || orderNodes < 0 || vehicles < 0 || highCount < 0 || mediumCount < 0 || lowCount < 0) {
            throw new IllegalArgumentException("Penalty inputs must not be negative");
        }
        for (long unit = 1; unit <= MAX_COST_UNIT_METRES; unit *= UNIT_STEP) {
            try {
                return withUnit(unit, maxArcMetres, orderNodes, vehicles, highCount, mediumCount, lowCount);
            } catch (ArithmeticException overflow) {
                // try a coarser cost unit
            }
        }
        throw new IllegalStateException("Drop penalties overflow int64 even with a cost unit of "
                + MAX_COST_UNIT_METRES + " m");
    }

    private static DropPenalties withUnit(long unit, long maxArcMetres, int orderNodes, int vehicles,
                                          int highCount, int mediumCount, int lowCount) {
        // Rounding is monotonic, so no scaled arc exceeds the scaled maximum.
        long maxArc = toCostUnits(maxArcMetres, unit);
        long routeBound = Math.multiplyExact(maxArc, (long) orderNodes + vehicles);
        long low = Math.addExact(routeBound, 1);
        long allLow = Math.multiplyExact(low, lowCount);
        long medium = Math.addExact(Math.addExact(allLow, routeBound), 1);
        long allMedium = Math.multiplyExact(medium, mediumCount);
        long high = Math.addExact(Math.addExact(Math.addExact(allMedium, allLow), routeBound), 1);
        long allHigh = Math.multiplyExact(high, highCount);
        long worstObjective = Math.addExact(Math.addExact(Math.addExact(allHigh, allMedium), allLow), routeBound);
        if (worstObjective > OBJECTIVE_BUDGET) {
            throw new ArithmeticException("objective budget exceeded");
        }
        return new DropPenalties(unit, low, medium, high);
    }

    /** Converts metres to this model's arc cost units (rounded to the nearest unit). */
    public long toCostUnits(long metres) {
        return toCostUnits(metres, costUnitMetres);
    }

    private static long toCostUnits(long metres, long unit) {
        return unit == 1 ? metres : (metres + unit / 2) / unit;
    }

    /** The drop penalty for an order of the given priority. */
    public long forPriority(Priority priority) {
        return switch (priority) {
            case HIGH -> high;
            case MEDIUM -> medium;
            case LOW -> low;
        };
    }
}
