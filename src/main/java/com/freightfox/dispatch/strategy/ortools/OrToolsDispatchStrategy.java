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
 * <p><b>Fallback.</b> {@link #plan} never throws for solver problems. It logs a WARN and returns
 * the injected fallback (greedy) strategy's result, whose {@code strategy()} names what actually
 * ran, when: there are more than {@code app.dispatch.ortools.max-orders} orders (the distance
 * matrix is N^2), the native library cannot be loaded, the solver finds no solution, or anything
 * unexpected fails in the solver path.
 *
 * <p><b>Determinism.</b> Guided Local Search runs until the time limit, so on a busy machine the
 * number of improvement steps, and therefore the plan, can differ slightly between runs. The
 * invariants (capacity, priorities, each order exactly once) always hold.
 */
@Component(StrategyNames.ORTOOLS)
public class OrToolsDispatchStrategy implements DispatchStrategy {

    private static final Logger log = LoggerFactory.getLogger(OrToolsDispatchStrategy.class);

    private final DispatchProperties properties;
    private final DispatchStrategy fallback;
    private final OrToolsNativeLoader nativeLoader;
    private final CvrpModelBuilder modelBuilder;
    private final DispatchResultAssembler assembler;
    private final CvrpSolver solver;

    /**
     * @param distanceCalculator distance between coordinates (Haversine)
     * @param properties         time limit and max-orders cap
     * @param fallback           strategy used when OR-Tools cannot or should not run (greedy)
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
        try {
            return solve(orders, vehicles).orElseGet(() -> {
                log.warn("OR-Tools found no solution within {}; using {}",
                        properties.ortools().timeLimit(), fallback.name());
                return fallback.plan(orders, vehicles);
            });
        } catch (RuntimeException | LinkageError e) {
            log.warn("OR-Tools failed ({}); using {}", e.toString(), fallback.name(), e);
            return fallback.plan(orders, vehicles);
        }
    }

    private Optional<DispatchResult> solve(List<DeliveryOrder> orders, List<Vehicle> vehicles) {
        int maxCapacity = vehicles.stream().mapToInt(Vehicle::getCapacity).max().orElse(0);
        List<DeliveryOrder> routable = new ArrayList<>(orders.size());
        List<UnassignedOrder> unassigned = new ArrayList<>();
        for (DeliveryOrder order : orders) {
            if (order.getPackageWeight() > maxCapacity) {
                unassigned.add(new UnassignedOrder(order, UnassignmentReasons.EXCEEDS_EVERY_VEHICLE));
            } else {
                routable.add(order);
            }
        }
        if (routable.isEmpty()) {
            return Optional.of(assembler.assemble(name(), vehicles, emptyStops(vehicles.size()), unassigned));
        }

        Duration timeLimit = properties.ortools().timeLimit();
        long startNanos = System.nanoTime();
        CvrpModel model = modelBuilder.build(routable, vehicles);
        Optional<CvrpSolution> solution = solver.solve(model, timeLimit);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (solution.isEmpty()) {
            return Optional.empty();
        }

        List<List<DeliveryOrder>> stops = toStops(model, solution.get());
        boolean[] served = new boolean[model.numOrders()];
        solution.get().routes().forEach(route -> route.forEach(orderIndex -> served[orderIndex] = true));
        int dropped = 0;
        for (int i = 0; i < served.length; i++) {
            if (!served[i]) {
                unassigned.add(new UnassignedOrder(routable.get(i), UnassignmentReasons.DROPPED_BY_OPTIMIZER));
                dropped++;
            }
        }
        log.info("OR-Tools solved: orders={}, vehicles={}, excludedTooHeavy={}, dropped={}, objective={}, "
                        + "status={}, costUnitMetres={}, solveTimeMs={}",
                orders.size(), vehicles.size(), orders.size() - routable.size(), dropped,
                solution.get().objective(), solution.get().status(), model.penalties().costUnitMetres(), elapsedMs);
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
