package com.freightfox.dispatch.testdata;

import com.freightfox.dispatch.config.TestDataProperties;
import com.freightfox.dispatch.exception.BadRequestException;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.UpsertResult;
import com.freightfox.dispatch.service.VehicleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Demo use case behind POST /api/dispatch/test-data: validates the requested size, generates a
 * dataset, REPLACES all stored orders and vehicles with it and reports how long that took.
 *
 * <p>The data is stored through the same {@link OrderService#upsertOrders} and
 * {@link VehicleService#upsertVehicles} used by the public POST endpoints, so the timing reflects
 * the real chunked upsert path. Existing data is deleted first so the next plan reflects exactly
 * the generated dataset. Delete and insert run in ONE transaction (the services join it; the
 * chunked upserter flushes and clears per chunk, so memory stays bounded), so a concurrent plan
 * request sees either the old data or the new data, never an empty or partial fleet.
 *
 * <p>Replacements are serialised with a lock so two concurrent calls cannot interleave their
 * delete and insert steps (this protects a single instance, which is what the demo runs on).
 */
@Service
public class TestDataService {

    static final String ORDERS_PARAM = "orders";
    static final String VEHICLES_PARAM = "vehicles";
    private static final int MIN_COUNT = 1;
    private static final String MESSAGE_SEPARATOR = "; ";
    /** Reason for an out-of-range count, e.g. "must be between 1 and 50000". */
    private static final String RANGE_REASON = "must be between %d and %d";
    private static final String MESSAGE_FORMAT = "%s %s";
    private static final String ERROR_FORMAT = "%s: %s";

    private static final Logger log = LoggerFactory.getLogger(TestDataService.class);

    private final TestDataGenerator generator;
    private final OrderService orderService;
    private final VehicleService vehicleService;
    private final TestDataProperties properties;
    private final ReentrantLock replaceLock = new ReentrantLock();

    public TestDataService(TestDataGenerator generator, OrderService orderService,
                           VehicleService vehicleService, TestDataProperties properties) {
        this.generator = generator;
        this.orderService = orderService;
        this.vehicleService = vehicleService;
        this.properties = properties;
    }

    /**
     * Generates {@code orderCount} orders and {@code vehicleCount} vehicles from {@code seed} and
     * replaces all stored orders and vehicles with them.
     *
     * @throws BadRequestException if orderCount is outside 1..maxOrders or vehicleCount is outside
     *                             1..maxVehicles (app.test-data.*)
     */
    @Transactional
    public TestDataResult replaceWithGeneratedData(int orderCount, int vehicleCount, long seed) {
        validate(orderCount, vehicleCount);

        replaceLock.lock();
        try {
            long start = System.nanoTime();
            GeneratedDataset dataset = generator.generate(orderCount, vehicleCount, seed);
            long generatedAt = System.nanoTime();

            orderService.deleteAllOrders();
            vehicleService.deleteAllVehicles();
            UpsertResult vehicles = vehicleService.upsertVehicles(dataset.vehicles());
            UpsertResult orders = orderService.upsertOrders(dataset.orders());
            long end = System.nanoTime();

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(end - start);
            log.info("Test data replaced: {} orders, {} vehicles, seed {} in {} ms (generate {} ms, store {} ms)",
                    orders.total(), vehicles.total(), seed, elapsedMs,
                    TimeUnit.NANOSECONDS.toMillis(generatedAt - start),
                    TimeUnit.NANOSECONDS.toMillis(end - generatedAt));
            return new TestDataResult(dataset.orders().size(), dataset.vehicles().size(), seed, elapsedMs);
        } finally {
            replaceLock.unlock();
        }
    }

    /**
     * Rejects out-of-range counts with message "orders must be between 1 and 50000" and errors
     * ["orders: must be between 1 and 50000"] (both parameters are reported when both are wrong).
     */
    private void validate(int orderCount, int vehicleCount) {
        List<String> messages = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        checkRange(ORDERS_PARAM, orderCount, properties.maxOrders(), messages, errors);
        checkRange(VEHICLES_PARAM, vehicleCount, properties.maxVehicles(), messages, errors);
        if (!errors.isEmpty()) {
            throw new BadRequestException(String.join(MESSAGE_SEPARATOR, messages), errors);
        }
    }

    private static void checkRange(String param, int value, int max, List<String> messages,
                                   List<String> errors) {
        if (value < MIN_COUNT || value > max) {
            String reason = RANGE_REASON.formatted(MIN_COUNT, max);
            messages.add(MESSAGE_FORMAT.formatted(param, reason));
            errors.add(ERROR_FORMAT.formatted(param, reason));
        }
    }
}
