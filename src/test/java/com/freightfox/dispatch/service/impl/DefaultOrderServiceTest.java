package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.config.DispatchProperties;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.exception.DuplicateIdException;
import com.freightfox.dispatch.repository.DeliveryOrderRepository;
import com.freightfox.dispatch.service.UpsertResult;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultOrderServiceTest {

    private static final int CHUNK_SIZE = 3;

    @Mock
    private DeliveryOrderRepository repository;
    @Mock
    private EntityManager entityManager;

    private DefaultOrderService service;

    static DispatchProperties properties(int chunkSize) {
        return new DispatchProperties(chunkSize,
                new DispatchProperties.Greedy(1000, Duration.ofMillis(25)),
                new DispatchProperties.OrTools(Duration.ofSeconds(3), 1000));
    }

    private static DeliveryOrder order(String id, int weight) {
        return new DeliveryOrder(id, 28.6, 77.2, "Address of " + id, weight, Priority.MEDIUM);
    }

    /** An order as it would come back from the database (different field values). */
    private static DeliveryOrder stored(String id) {
        DeliveryOrder order = new DeliveryOrder(id, 1.0, 2.0, "Old address", 1, Priority.LOW);
        return order;
    }

    @BeforeEach
    void setUp() {
        service = new DefaultOrderService(repository, new ChunkedUpserter(entityManager, properties(CHUNK_SIZE)));
    }

    private void givenStored(DeliveryOrder... existing) {
        Map<String, DeliveryOrder> byId = new HashMap<>();
        for (DeliveryOrder order : existing) {
            byId.put(order.getOrderId(), order);
        }
        when(repository.findAllById(anyIterable())).thenAnswer(invocation -> {
            Iterable<String> ids = invocation.getArgument(0);
            return StreamSupport.stream(ids.spliterator(), false).filter(byId::containsKey).map(byId::get).toList();
        });
    }

    @Test
    void countsInsertsAndUpdatesAndUsesOneFindAllByIdPerChunk() {
        DeliveryOrder existing2 = stored("ORD2");
        DeliveryOrder existing5 = stored("ORD5");
        givenStored(existing2, existing5);
        List<DeliveryOrder> incoming = IntStream.rangeClosed(1, 7).mapToObj(i -> order("ORD" + i, i * 10)).toList();

        UpsertResult result = service.upsertOrders(incoming);

        assertThat(result).isEqualTo(new UpsertResult(5, 2));
        assertThat(result.total()).isEqualTo(7);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<String>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(repository, times(3)).findAllById(ids.capture());
        assertThat(ids.getAllValues()).containsExactly(
                List.of("ORD1", "ORD2", "ORD3"), List.of("ORD4", "ORD5", "ORD6"), List.of("ORD7"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DeliveryOrder>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository, times(3)).saveAll(saved.capture());
        assertThat(saved.getAllValues()).extracting(list -> list.stream().map(DeliveryOrder::getOrderId).toList())
                .containsExactly(List.of("ORD1", "ORD3"), List.of("ORD4", "ORD6"), List.of("ORD7"));

        verify(entityManager, times(3)).flush();
        verify(entityManager, times(3)).clear();
    }

    @Test
    void updatesExistingManagedEntitiesInPlace() {
        DeliveryOrder existing = stored("ORD1");
        givenStored(existing);
        DeliveryOrder incoming = new DeliveryOrder("ORD1", 12.97, 77.59, "MG Road", 42, Priority.HIGH);

        UpsertResult result = service.upsertOrders(List.of(incoming));

        assertThat(result).isEqualTo(new UpsertResult(0, 1));
        assertThat(existing.getLatitude()).isEqualTo(12.97);
        assertThat(existing.getLongitude()).isEqualTo(77.59);
        assertThat(existing.getAddress()).isEqualTo("MG Road");
        assertThat(existing.getPackageWeight()).isEqualTo(42);
        assertThat(existing.getPriority()).isEqualTo(Priority.HIGH);
        // Nothing new: no saveAll; the update is flushed by dirty checking.
        verify(repository, never()).saveAll(anyIterable());
        verify(entityManager).flush();
    }

    @Test
    void eachChunkIsLoadedSavedFlushedAndClearedInThatOrder() {
        givenStored();
        List<DeliveryOrder> incoming = List.of(order("A", 1), order("B", 2), order("C", 3), order("D", 4));

        service.upsertOrders(incoming);

        InOrder inOrder = inOrder(repository, entityManager);
        for (int chunk = 0; chunk < 2; chunk++) {
            inOrder.verify(repository).findAllById(anyIterable());
            inOrder.verify(repository).saveAll(anyIterable());
            inOrder.verify(entityManager).flush();
            inOrder.verify(entityManager).clear();
        }
    }

    @Test
    void rejectsDuplicateIdsBeforeTouchingTheDatabase() {
        List<DeliveryOrder> incoming = List.of(order("ORD001", 1), order("ORD002", 1), order("ORD001", 2),
                order("ORD002", 3), order("ORD001", 4));

        assertThatThrownBy(() -> service.upsertOrders(incoming))
                .isInstanceOf(DuplicateIdException.class)
                .hasMessage("Duplicate orderId values in request")
                .satisfies(ex -> assertThat(((DuplicateIdException) ex).getErrors()).containsExactly(
                        "orders: duplicate orderId 'ORD001'", "orders: duplicate orderId 'ORD002'"));
        verifyNoInteractions(repository, entityManager);
    }

    @Test
    void emptyBatchDoesNothing() {
        assertThat(service.upsertOrders(List.of())).isEqualTo(new UpsertResult(0, 0));
        assertThat(service.upsertOrders(null)).isEqualTo(new UpsertResult(0, 0));
        verifyNoInteractions(repository, entityManager);
    }

    @Test
    void largeBatchUsesCeilOfSizeOverChunkQueries() {
        givenStored();
        List<DeliveryOrder> incoming = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            incoming.add(order("ID" + i, 1));
        }

        assertThat(service.upsertOrders(incoming)).isEqualTo(new UpsertResult(10, 0));

        verify(repository, times(4)).findAllById(anyIterable());
        verify(repository, times(4)).saveAll(anyIterable());
    }

    @Test
    void findAllSortsByOrderId() {
        List<DeliveryOrder> stored = List.of(stored("A"), stored("B"));
        when(repository.findAll(Sort.by("orderId"))).thenReturn(stored);

        assertThat(service.findAllOrders()).isSameAs(stored);
    }

    @Test
    void countDelegatesToRepository() {
        when(repository.count()).thenReturn(42L);

        assertThat(service.countOrders()).isEqualTo(42L);
    }

    @Test
    void deleteAllUsesOneBulkDelete() {
        service.deleteAllOrders();

        verify(repository).deleteAllInBatch();
        verify(repository, never()).deleteAll();
    }
}
