package com.freightfox.dispatch.service;

import com.freightfox.dispatch.entity.DeliveryOrder;

import java.util.List;

/** Persistence use cases for delivery orders. */
public interface OrderService {

    /**
     * Inserts new orders and updates existing ones (matched by orderId), in chunks.
     *
     * @throws com.freightfox.dispatch.exception.BadRequestException if the batch contains
     *         duplicate orderIds
     */
    UpsertResult upsertOrders(List<DeliveryOrder> orders);

    /** All orders, sorted by orderId. */
    List<DeliveryOrder> findAllOrders();

    long countOrders();

    void deleteAllOrders();
}
