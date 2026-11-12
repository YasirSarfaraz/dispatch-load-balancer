package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.repository.DeliveryOrderRepository;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.UpsertResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** {@link OrderService} backed by JPA with a chunked, JDBC-batched upsert. */
@Service
public class DefaultOrderService implements OrderService {

    static final String ID_FIELD = "orderId";
    static final String LIST_FIELD = "orders";

    private static final Logger log = LoggerFactory.getLogger(DefaultOrderService.class);
    private static final Sort BY_ID = Sort.by(ID_FIELD);

    private final DeliveryOrderRepository repository;
    private final ChunkedUpserter upserter;
    private final ChunkedUpserter.Target<DeliveryOrder> target;

    public DefaultOrderService(DeliveryOrderRepository repository, ChunkedUpserter upserter) {
        this.repository = repository;
        this.upserter = upserter;
        this.target = new ChunkedUpserter.Target<>(repository, DeliveryOrder::updateFrom, ID_FIELD, LIST_FIELD);
    }

    @Override
    @Transactional
    public UpsertResult upsertOrders(List<DeliveryOrder> orders) {
        long start = System.nanoTime();
        UpsertResult result = upserter.upsert(orders, target);
        log.info("Upserted delivery orders: {} inserted, {} updated in {} ms",
                result.inserted(), result.updated(), (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryOrder> findAllOrders() {
        return repository.findAll(BY_ID);
    }

    @Override
    @Transactional(readOnly = true)
    public long countOrders() {
        return repository.count();
    }

    @Override
    @Transactional
    public void deleteAllOrders() {
        repository.deleteAllInBatch();
        log.info("Deleted all delivery orders");
    }
}
