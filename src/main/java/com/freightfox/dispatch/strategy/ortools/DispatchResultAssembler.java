package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.distance.DistanceCalculator;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.VehicleRoute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Turns per-vehicle stop sequences into a {@link DispatchResult}: one {@link VehicleRoute} per
 * input vehicle (input order), load and open-route distance in km recomputed on the real
 * coordinates (not the solver's rounded metres), and unassigned orders sorted by priority then
 * orderId. Also re-checks capacity, so a broken solution can never leave the strategy.
 */
final class DispatchResultAssembler {

    /** Priority (HIGH first), then orderId: the order required by the DispatchStrategy contract. */
    static final Comparator<UnassignedOrder> UNASSIGNED_ORDER = Comparator
            .comparing((UnassignedOrder u) -> u.order().getPriority())
            .thenComparing(u -> u.order().getOrderId());

    private final DistanceCalculator distanceCalculator;

    DispatchResultAssembler(DistanceCalculator distanceCalculator) {
        this.distanceCalculator = Objects.requireNonNull(distanceCalculator, "distanceCalculator");
    }

    /**
     * @param strategy        name reported in the result
     * @param vehicles        input vehicles, in input order
     * @param stopsPerVehicle stops of {@code vehicles.get(v)} in visiting order, same size as vehicles
     * @param unassigned      orders not on any route
     * @throws IllegalStateException if a route exceeds its vehicle's capacity
     */
    DispatchResult assemble(String strategy, List<Vehicle> vehicles, List<List<DeliveryOrder>> stopsPerVehicle,
                            List<UnassignedOrder> unassigned) {
        List<VehicleRoute> routes = new ArrayList<>(vehicles.size());
        for (int v = 0; v < vehicles.size(); v++) {
            routes.add(route(vehicles.get(v), stopsPerVehicle.get(v)));
        }
        List<UnassignedOrder> sorted = unassigned.stream().sorted(UNASSIGNED_ORDER).toList();
        return new DispatchResult(strategy, routes, sorted);
    }

    private VehicleRoute route(Vehicle vehicle, List<DeliveryOrder> stops) {
        if (stops.isEmpty()) {
            return VehicleRoute.empty(vehicle);
        }
        int load = stops.stream().mapToInt(DeliveryOrder::getPackageWeight).sum();
        if (load > vehicle.getCapacity()) {
            throw new IllegalStateException("Solver route for " + vehicle.getVehicleId() + " carries " + load
                    + " > capacity " + vehicle.getCapacity());
        }
        return new VehicleRoute(vehicle, stops, load, openRouteKm(vehicle, stops));
    }

    /** vehicle location -> stop1 -> ... -> last stop (no return trip). */
    private double openRouteKm(Vehicle vehicle, List<DeliveryOrder> stops) {
        double km = 0.0;
        double lat = vehicle.getCurrentLatitude();
        double lon = vehicle.getCurrentLongitude();
        for (DeliveryOrder stop : stops) {
            km += distanceCalculator.distanceKm(lat, lon, stop.getLatitude(), stop.getLongitude());
            lat = stop.getLatitude();
            lon = stop.getLongitude();
        }
        return km;
    }
}
