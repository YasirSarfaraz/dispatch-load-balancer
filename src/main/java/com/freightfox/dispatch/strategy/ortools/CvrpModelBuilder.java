package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.distance.DistanceCalculator;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a {@link CvrpModel}: node layout, distance matrix in whole metres (then in the cost unit
 * chosen by {@link DropPenalties}), demands, capacities and drop penalties.
 *
 * <p>The matrix is {@code (V + N + 1)^2} longs, which is why the strategy caps N (max-orders).
 * Distances come from the {@link DistanceCalculator} (Haversine in production), which is symmetric,
 * so each pair is computed once and mirrored.
 */
final class CvrpModelBuilder {

    private static final double METRES_PER_KM = 1000.0;

    private final DistanceCalculator distanceCalculator;

    CvrpModelBuilder(DistanceCalculator distanceCalculator) {
        this.distanceCalculator = Objects.requireNonNull(distanceCalculator, "distanceCalculator");
    }

    /**
     * @param orders   orders to route; each must fit at least one vehicle (the caller filters out
     *                 orders heavier than every vehicle)
     * @param vehicles the fleet, in input order
     */
    CvrpModel build(List<DeliveryOrder> orders, List<Vehicle> vehicles) {
        int numVehicles = vehicles.size();
        int numOrders = orders.size();
        int endNode = numVehicles + numOrders;
        int numNodes = endNode + 1;

        double[] lat = new double[numNodes];
        double[] lon = new double[numNodes];
        long[] demands = new long[numNodes];
        for (int v = 0; v < numVehicles; v++) {
            lat[v] = vehicles.get(v).getCurrentLatitude();
            lon[v] = vehicles.get(v).getCurrentLongitude();
        }
        for (int i = 0; i < numOrders; i++) {
            DeliveryOrder order = orders.get(i);
            int node = numVehicles + i;
            lat[node] = order.getLatitude();
            lon[node] = order.getLongitude();
            demands[node] = order.getPackageWeight();
        }

        // Metres between every pair of real nodes; row/column of the dummy end stay 0 (open routes).
        long[][] matrix = new long[numNodes][numNodes];
        long maxArcMetres = 0;
        for (int i = 0; i < endNode; i++) {
            for (int j = i + 1; j < endNode; j++) {
                long metres = Math.round(distanceCalculator.distanceKm(lat[i], lon[i], lat[j], lon[j]) * METRES_PER_KM);
                matrix[i][j] = metres;
                matrix[j][i] = metres;
                maxArcMetres = Math.max(maxArcMetres, metres);
            }
        }

        Map<Priority, Integer> counts = countByPriority(orders);
        DropPenalties penalties = DropPenalties.compute(maxArcMetres, numOrders, numVehicles,
                counts.get(Priority.HIGH), counts.get(Priority.MEDIUM), counts.get(Priority.LOW));
        if (penalties.costUnitMetres() != 1) {
            for (long[] row : matrix) {
                for (int j = 0; j < row.length; j++) {
                    row[j] = penalties.toCostUnits(row[j]);
                }
            }
        }

        long[] capacities = vehicles.stream().mapToLong(Vehicle::getCapacity).toArray();
        return new CvrpModel(orders, vehicles, matrix, demands, capacities, penalties);
    }

    private static Map<Priority, Integer> countByPriority(List<DeliveryOrder> orders) {
        Map<Priority, Integer> counts = new EnumMap<>(Priority.class);
        for (Priority priority : Priority.values()) {
            counts.put(priority, 0);
        }
        for (DeliveryOrder order : orders) {
            counts.merge(order.getPriority(), 1, Integer::sum);
        }
        return counts;
    }
}
