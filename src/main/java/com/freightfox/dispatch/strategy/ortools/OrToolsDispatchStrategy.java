package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.config.DispatchProperties;
import com.freightfox.dispatch.distance.DistanceCalculator;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.DispatchStrategy;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Dispatch strategy that solves the Capacitated Vehicle Routing Problem (CVRP) with Google
 * OR-Tools: open routes (each vehicle starts at its location, all end at a zero-cost dummy node),
 * a capacity dimension, and an optional visit per order whose drop penalty encodes the priority
 * order HIGH, then MEDIUM, then LOW (see {@link DropPenalties}).
 *
 * <p>Orders heavier than every vehicle are excluded up front and reported with
 * {@link UnassignmentReasons#EXCEEDS_EVERY_VEHICLE}; orders the solver drops are reported with
 * {@link UnassignmentReasons#DROPPED_BY_OPTIMIZER}.
 *
 * <p><b>Warm start from greedy.</b> A PATH_CHEAPEST_ARC first solution is built from arc costs
 * only and ignores the drop penalties; when capacity is scarce, Guided Local Search cannot swap
 * enough served MEDIUM/LOW orders for dropped HIGH ones within a few seconds (measured on 1,000 x 50
 * generated orders: 110 HIGH orders dropped at 3 s, while greedy served 110 more HIGH orders on the
 * same data). So the injected fallback (greedy) strategy plans first, which takes milliseconds, and
 * its routes are the <em>seed</em> of the search ({@link SeedRoutes}, then
 * {@code readAssignmentFromRoutes} + {@code solveFromAssignmentWithParameters} in
 * {@link OrToolsCvrpSolver}). Local search only accepts improvements of the objective, and the drop
 * penalties are lexicographic, so the solver result never drops more HIGH (then MEDIUM, then LOW)
 * orders than greedy; the remaining time serves extra orders and shortens the routes.
 * PATH_CHEAPEST_ARC is used only when there is no usable seed (greedy assigned nothing, or OR-Tools
 * rejected the seed).
 *
 * <p><b>Priority guard.</b> After solving, the unassigned counts per priority of the OR-Tools
 * result are compared lexicographically with the greedy plan's ({@link UnassignedCounts}). If
 * OR-Tools is worse (which the warm start should make impossible), a WARN is logged and the greedy
 * plan is returned; equal or better keeps the OR-Tools plan.
 *
 * <p><b>Fallback.</b> {@link #plan} never throws for solver problems. It logs a WARN and returns
 * the greedy plan, whose {@code strategy()} names what actually ran, when: there are more than
 * {@code app.dispatch.ortools.max-orders} orders (the distance matrix is N^2), the native library
 * cannot be loaded, the solver finds no solution, anything unexpected fails in the solver path, or
 * the guard above rejects the solver result. Within one call greedy runs at most once: the plan
 * computed for the seed is reused by every fallback after it.
 *
 * <p><b>Determinism.</b> Guided Local Search runs until the time limit, so on a busy machine the
 * number of improvement steps, and therefore the plan, can differ slightly between runs. The
 * invariants (capacity, priorities, each order exactly once) always hold.
 */
@Component(StrategyNames.ORTOOLS)
public class OrToolsDispatchStrategy implements DispatchStrategy {

    private static final Logger log = LoggerFactory.getLogger(OrToolsDispatchStrategy.class);

    private static final long NANOS_PER_MS = 1_000_000L;

    private final DispatchProperties properties;
    private final DispatchStrategy fallback;
    private final OrToolsNativeLoader nativeLoader;
    private final CvrpModelBuilder modelBuilder;
    private final DispatchResultAssembler assembler;
    private final CvrpSolver solver;

    /**
     * @param distanceCalculator distance between coordinates (Haversine)
     * @param properties         time limit and max-orders cap
     * @param fallback           greedy: the warm-start seed, and the plan returned when OR-Tools cannot or
     *                           should not run or its result fails the priority guard
     * @param nativeLoader       lazy, cached loader of the OR-Tools native libraries
     */
    @Autowired
    public OrToolsDispatchStrategy(DistanceCalculator distanceCalculator, DispatchProperties properties,
                                   @Qualifier(StrategyNames.GREEDY) DispatchStrategy fallback,
                                   OrToolsNativeLoader nativeLoader) {
        this(distanceCalculator, properties, fallback, nativeLoader, new OrToolsCvrpSolver());
    }

    /** Test seam: replaces the OR-Tools solver call. */
    OrToolsDispatchStrategy(DistanceCalculator distanceCalculator, DispatchProperties properties,
                            DispatchStrategy fallback, OrToolsNativeLoader nativeLoader, CvrpSolver solver) {
        Objects.requireNonNull(distanceCalculator, "distanceCalculator");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.nativeLoader = Objects.requireNonNull(nativeLoader, "nativeLoader");
        this.solver = Objects.requireNonNull(solver, "solver");
        this.modelBuilder = new CvrpModelBuilder(distanceCalculator);
        this.assembler = new DispatchResultAssembler(distanceCalculator);
    }

    @Override
    public String name() {
        return StrategyNames.ORTOOLS;
    }

    @Override
    public DispatchResult plan(List<DeliveryOrder> orders, List<Vehicle> vehicles) {
        if (orders.isEmpty()) {
            return assembler.assemble(name(), vehicles, emptyStops(vehicles.size()), List.of());
        }
        int maxOrders = properties.ortools().maxOrders();
        if (orders.size() > maxOrders) {
            log.warn("OR-Tools skipped: {} orders exceed the cap of {} (distance matrix grows as N^2); using {}",
                    orders.size(), maxOrders, fallback.name());
            return fallback.plan(orders, vehicles);
        }
        if (!nativeLoader.ensureLoaded()) {
            log.warn("OR-Tools skipped: native library unavailable; using {}", fallback.name());
            return fallback.plan(orders, vehicles);
        }

        int maxCapacity = vehicles.stream().mapToInt(Vehicle::getCapacity).max().orElse(0);
        List<DeliveryOrder> routable = new ArrayList<>(orders.size());
        List<UnassignedOrder> tooHeavy = new ArrayList<>();
        for (DeliveryOrder order : orders) {
            if (order.getPackageWeight() > maxCapacity) {
                tooHeavy.add(new UnassignedOrder(order, UnassignmentReasons.EXCEEDS_EVERY_VEHICLE));
            } else {
                routable.add(order);
            }
        }
        if (routable.isEmpty()) {
            return assembler.assemble(name(), vehicles, emptyStops(vehicles.size()), tooHeavy);
        }

        // The seed of the search, and the plan every fallback below returns (greedy runs once per call).
        DispatchResult greedy = fallback.plan(orders, vehicles);
        try {
            Optional<DispatchResult> solved = solve(orders.size(), routable, tooHeavy, vehicles, greedy);
            if (solved.isEmpty()) {
                log.warn("OR-Tools found no solution within {}; using {}",
                        properties.ortools().timeLimit(), fallback.name());
                return greedy;
            }
            return keepPriorityOrder(solved.get(), greedy);
        } catch (RuntimeException | LinkageError e) {
            log.warn("OR-Tools failed ({}); using {}", e.toString(), fallback.name(), e);
            return greedy;
        }
    }

    /**
     * The priority guard: returns the greedy plan if the OR-Tools plan leaves lexicographically more
     * orders unassigned (HIGH, then MEDIUM, then LOW), otherwise the OR-Tools plan.
     */
    private DispatchResult keepPriorityOrder(DispatchResult orTools, DispatchResult greedy) {
        UnassignedCounts orToolsCounts = UnassignedCounts.of(orTools);
        UnassignedCounts greedyCounts = UnassignedCounts.of(greedy);
        if (orToolsCounts.isWorseThan(greedyCounts)) {
            log.warn("OR-Tools plan leaves more higher-priority orders unassigned than {} (unassigned H/M/L {} vs {}); "
                    + "using {}", fallback.name(), orToolsCounts, greedyCounts, fallback.name());
            return greedy;
        }
        return orTools;
    }

    private Optional<DispatchResult> solve(int orderCount, List<DeliveryOrder> routable,
                                           List<UnassignedOrder> tooHeavy, List<Vehicle> vehicles,
                                           DispatchResult seedPlan) {
        Duration timeLimit = properties.ortools().timeLimit();
        long startNanos = System.nanoTime();
        CvrpModel model = modelBuilder.build(routable, vehicles);
        List<List<Integer>> seed = SeedRoutes.fromPlan(model, seedPlan);
        Optional<CvrpSolution> solution = solver.solve(model, seed, timeLimit);
        long elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MS;
        if (solution.isEmpty()) {
            return Optional.empty();
        }

        List<List<DeliveryOrder>> stops = toStops(model, solution.get());
        List<UnassignedOrder> unassigned = new ArrayList<>(tooHeavy);
        boolean[] served = new boolean[model.numOrders()];
        solution.get().routes().forEach(route -> route.forEach(orderIndex -> served[orderIndex] = true));
        int dropped = 0;
        for (int i = 0; i < served.length; i++) {
            if (!served[i]) {
                unassigned.add(new UnassignedOrder(routable.get(i), UnassignmentReasons.DROPPED_BY_OPTIMIZER));
                dropped++;
            }
        }
        log.info("OR-Tools solved: orders={}, vehicles={}, excludedTooHeavy={}, dropped={}, seedStops={}, "
                        + "warmStarted={}, objective={}, status={}, costUnitMetres={}, solveTimeMs={}",
                orderCount, vehicles.size(), tooHeavy.size(), dropped, seed.stream().mapToInt(List::size).sum(),
                solution.get().warmStarted(), solution.get().objective(), solution.get().status(),
                model.penalties().costUnitMetres(), elapsedMs);
        return Optional.of(assembler.assemble(name(), vehicles, stops, unassigned));
    }

    /** Maps solver order indices back to orders; rejects a solution that visits an order twice. */
    private static List<List<DeliveryOrder>> toStops(CvrpModel model, CvrpSolution solution) {
        if (solution.routes().size() != model.numVehicles()) {
            throw new IllegalStateException("Solution has " + solution.routes().size() + " routes for "
                    + model.numVehicles() + " vehicles");
        }
        boolean[] seen = new boolean[model.numOrders()];
        List<List<DeliveryOrder>> stops = new ArrayList<>(model.numVehicles());
        for (List<Integer> route : solution.routes()) {
            List<DeliveryOrder> routeStops = new ArrayList<>(route.size());
            for (int orderIndex : route) {
                if (seen[orderIndex]) {
                    throw new IllegalStateException("Solution visits " + model.orders().get(orderIndex).getOrderId()
                            + " more than once");
                }
                seen[orderIndex] = true;
                routeStops.add(model.orders().get(orderIndex));
            }
            stops.add(routeStops);
        }
        return stops;
    }

    private static List<List<DeliveryOrder>> emptyStops(int vehicles) {
        return Collections.nCopies(vehicles, List.of());
    }
}
