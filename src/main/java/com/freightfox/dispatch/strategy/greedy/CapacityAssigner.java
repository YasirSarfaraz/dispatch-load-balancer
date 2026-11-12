package com.freightfox.dispatch.strategy.greedy;

import com.freightfox.dispatch.distance.HaversineTable;

/**
 * Steps 2-4 of the greedy algorithm, on primitive arrays so the O(orders x vehicles) loop does
 * not allocate.
 *
 * <p>Orders must already be in {@link DispatchOrdering#ASSIGNMENT_ORDER} and vehicles in
 * {@link DispatchOrdering#VEHICLE_ID_ORDER}. Every order goes, in turn, to the vehicle with enough
 * remaining capacity whose route end (initially its current location, then its last assigned
 * order) is nearest; equal distances go to the vehicle that comes first, i.e. the smaller
 * vehicleId. The vehicle's route end then moves to the order.
 *
 * <p>Distances are compared through the Haversine term {@code h}, which is strictly increasing in
 * distance, so no square root or arcsine is evaluated in the loop.
 */
final class CapacityAssigner {

    /** Marker in {@link #assign} output for an order no vehicle could take. */
    static final int UNASSIGNED = -1;

    private CapacityAssigner() {
    }

    /**
     * @param weights         package weight per order, in assignment order
     * @param capacities      capacity per vehicle, in vehicleId order
     * @param points          table whose points {@code 0..orders-1} are the orders and
     *                        {@code vehicleOffset + v} is vehicle {@code v}'s current location
     * @param vehicleOffset   index in {@code points} of the first vehicle
     * @return for every order the vehicle index it was assigned to, or {@link #UNASSIGNED}
     */
    static int[] assign(int[] weights, int[] capacities, HaversineTable points, int vehicleOffset) {
        int orderCount = weights.length;
        int vehicleCount = capacities.length;
        int[] remaining = capacities.clone();
        int[] routeEnd = new int[vehicleCount];
        for (int v = 0; v < vehicleCount; v++) {
            routeEnd[v] = vehicleOffset + v;
        }

        int[] assignedVehicle = new int[orderCount];
        for (int order = 0; order < orderCount; order++) {
            int weight = weights[order];
            int bestVehicle = UNASSIGNED;
            double bestTerm = Double.POSITIVE_INFINITY;
            for (int v = 0; v < vehicleCount; v++) {
                if (remaining[v] < weight) {
                    continue;
                }
                double term = points.haversineTerm(routeEnd[v], order);
                // Strictly smaller: on a tie the earlier vehicle (smaller vehicleId) is kept.
                if (term < bestTerm) {
                    bestTerm = term;
                    bestVehicle = v;
                }
            }
            assignedVehicle[order] = bestVehicle;
            if (bestVehicle != UNASSIGNED) {
                remaining[bestVehicle] -= weight;
                routeEnd[bestVehicle] = order;
            }
        }
        return assignedVehicle;
    }
}
