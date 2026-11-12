package com.freightfox.dispatch.service.impl;

import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.exception.DuplicateIdException;
import com.freightfox.dispatch.repository.VehicleRepository;
import com.freightfox.dispatch.service.UpsertResult;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultVehicleServiceTest {

    @Mock
    private VehicleRepository repository;
    @Mock
    private EntityManager entityManager;

    private DefaultVehicleService service;

    private static Vehicle vehicle(String id, int capacity) {
        return new Vehicle(id, capacity, 28.6, 77.2, "Depot " + id);
    }

    @BeforeEach
    void setUp() {
        service = new DefaultVehicleService(repository,
                new ChunkedUpserter(entityManager, DefaultOrderServiceTest.properties(3)));
    }

    @Test
    void countsInsertsAndUpdatesAndAppliesUpdateFrom() {
        Vehicle existing = new Vehicle("VEH3", 10, 0, 0, "Old depot");
        when(repository.findAllById(anyIterable())).thenAnswer(invocation -> {
            Iterable<String> ids = invocation.getArgument(0);
            return StreamSupport.stream(ids.spliterator(), false).filter("VEH3"::equals).map(id -> existing).toList();
        });
        List<Vehicle> incoming = IntStream.rangeClosed(1, 7).mapToObj(i -> vehicle("VEH" + i, i * 100)).toList();

        UpsertResult result = service.upsertVehicles(incoming);

        assertThat(result).isEqualTo(new UpsertResult(6, 1));
        assertThat(existing.getCapacity()).isEqualTo(300);
        assertThat(existing.getCurrentLatitude()).isEqualTo(28.6);
        assertThat(existing.getCurrentLongitude()).isEqualTo(77.2);
        assertThat(existing.getCurrentAddress()).isEqualTo("Depot VEH3");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<String>> ids = ArgumentCaptor.forClass(Iterable.class);
        verify(repository, times(3)).findAllById(ids.capture());
        assertThat(ids.getAllValues()).containsExactly(
                List.of("VEH1", "VEH2", "VEH3"), List.of("VEH4", "VEH5", "VEH6"), List.of("VEH7"));
        verify(entityManager, times(3)).flush();
        verify(entityManager, times(3)).clear();
    }

    @Test
    void rejectsDuplicateVehicleIds() {
        List<Vehicle> incoming = List.of(vehicle("VEH001", 1), vehicle("VEH001", 2));

        assertThatThrownBy(() -> service.upsertVehicles(incoming))
                .isInstanceOf(DuplicateIdException.class)
                .hasMessage("Duplicate vehicleId values in request")
                .satisfies(ex -> assertThat(((DuplicateIdException) ex).getErrors())
                        .containsExactly("vehicles: duplicate vehicleId 'VEH001'"));
        verifyNoInteractions(repository, entityManager);
    }

    @Test
    void findAllSortsByVehicleId() {
        List<Vehicle> stored = List.of(vehicle("A", 1));
        when(repository.findAll(Sort.by("vehicleId"))).thenReturn(stored);

        assertThat(service.findAllVehicles()).isSameAs(stored);
    }

    @Test
    void countDelegatesToRepository() {
        when(repository.count()).thenReturn(5L);

        assertThat(service.countVehicles()).isEqualTo(5L);
    }

    @Test
    void deleteAllUsesOneBulkDelete() {
        service.deleteAllVehicles();

        verify(repository).deleteAllInBatch();
        verify(repository, never()).deleteAll();
    }
}
