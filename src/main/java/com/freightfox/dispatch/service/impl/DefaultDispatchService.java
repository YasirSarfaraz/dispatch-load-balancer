package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.dto.response.DispatchPlanResponse;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.exception.NoVehiclesAvailableException;
import com.freightfox.dispatch.mapper.DispatchPlanMapper;
import com.freightfox.dispatch.service.DispatchService;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.VehicleService;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.DispatchStrategy;
import com.freightfox.dispatch.strategy.registry.DispatchStrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds a dispatch plan on demand: resolve the strategy, load vehicles and orders (each in its own
 * short read-only transaction inside the services), run the algorithm outside any transaction,
 * then map the result to the API response. The plan is not persisted.
 */
@Service
public class DefaultDispatchService implements DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDispatchService.class);

    private final DispatchStrategyRegistry registry;
    private final OrderService orderService;
    private final VehicleService vehicleService;
    private final DispatchPlanMapper mapper;

    public DefaultDispatchService(DispatchStrategyRegistry registry, OrderService orderService,
                                  VehicleService vehicleService, DispatchPlanMapper mapper) {
        this.registry = registry;
        this.orderService = orderService;
        this.vehicleService = vehicleService;
        this.mapper = mapper;
    }

    @Override
    public DispatchPlanResponse generatePlan(String strategyName) {
        // Resolve first: an unknown strategy is a 400 even when the database is empty.
        DispatchStrategy strategy = registry.get(strategyName);

        List<Vehicle> vehicles = vehicleService.findAllVehicles();
        if (vehicles.isEmpty()) {
            throw new NoVehiclesAvailableException();
        }
        List<DeliveryOrder> orders = orderService.findAllOrders();

        long start = System.nanoTime();
        DispatchResult result = strategy.plan(orders, vehicles);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        log.info("Dispatch plan: requested={}, ran={}, orders={}, vehicles={}, assigned={}, unassigned={}, "
                        + "totalDistanceKm={}, elapsedMs={}",
                strategy.name(), result.strategy(), orders.size(), vehicles.size(), result.assignedCount(),
                result.unassignedOrders().size(), DispatchPlanMapper.formatDistance(result.totalDistanceKm()),
                elapsedMs);
        return mapper.toResponse(result);
    }
}
