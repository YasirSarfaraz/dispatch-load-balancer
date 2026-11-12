package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.repository.DeliveryOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reproduces, deterministically and against the real database, what happens when another request
 * inserts the same new ID between this request's findAllById and its flush (a concurrent upsert
 * race): the primary-key violation must surface as Spring's {@link DataIntegrityViolationException}
 * (mapped to 409 by the global handler), not as a raw Hibernate exception (which would be a 500).
 */
@SpringBootTest
class ChunkedUpserterConflictIntegrationTest {

    private static final String FIND_ALL_BY_ID = "findAllById";

    @Autowired
    private ChunkedUpserter upserter;

    @Autowired
    private DeliveryOrderRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void clean() {
        repository.deleteAllInBatch();
    }

    @Test
    void insertRaceOnSameIdIsTranslatedToDataIntegrityViolation() {
        repository.saveAndFlush(order("RACE-1", 5));

        // A repository that "did not see" the existing row, exactly like a request whose
        // findAllById ran before a concurrent request committed the same ID.
        JpaRepository<DeliveryOrder, String> blindRepository = blindToExistingRows(repository);
        ChunkedUpserter.Target<DeliveryOrder> target =
                new ChunkedUpserter.Target<>(blindRepository, DeliveryOrder::updateFrom, "orderId", "orders");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> upserter.upsert(List.of(order("RACE-1", 7)), target)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.findById("RACE-1")).get()
                .extracting(DeliveryOrder::getPackageWeight).isEqualTo(5);
    }

    @SuppressWarnings("unchecked")
    private static JpaRepository<DeliveryOrder, String> blindToExistingRows(DeliveryOrderRepository delegate) {
        return (JpaRepository<DeliveryOrder, String>) Proxy.newProxyInstance(
                JpaRepository.class.getClassLoader(),
                new Class<?>[]{JpaRepository.class},
                (proxy, method, args) -> {
                    if (FIND_ALL_BY_ID.equals(method.getName())) {
                        return List.of();
                    }
                    return invoke(delegate, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static DeliveryOrder order(String id, int weight) {
        return new DeliveryOrder(id, 28.6139, 77.2090, "Connaught Place, Delhi, India", weight, Priority.HIGH);
    }
}
