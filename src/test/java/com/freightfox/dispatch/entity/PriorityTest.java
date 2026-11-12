package com.freightfox.dispatch.entity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityTest {

    @Test
    void declarationOrderIsDispatchOrder() {
        List<Priority> byRank = Arrays.stream(Priority.values())
                .sorted(Comparator.comparingInt(Priority::rank))
                .toList();

        assertThat(byRank).containsExactly(Priority.HIGH, Priority.MEDIUM, Priority.LOW);
        assertThat(Priority.HIGH.compareTo(Priority.LOW)).isNegative();
    }

    @Test
    void allowedValuesListsEveryConstant() {
        assertThat(Priority.allowedValues()).isEqualTo("HIGH, MEDIUM, LOW");
    }

    @Test
    void entitiesAreNewUntilPersistedOrLoaded() {
        DeliveryOrder order = new DeliveryOrder("ORD1", 1, 2, "addr", 5, Priority.LOW);
        Vehicle vehicle = new Vehicle("VEH1", 10, 1, 2, "addr");

        assertThat(order.isNew()).isTrue();
        assertThat(vehicle.isNew()).isTrue();

        order.markNotNew();
        vehicle.markNotNew();

        assertThat(order.isNew()).isFalse();
        assertThat(vehicle.isNew()).isFalse();
        assertThat(order.getId()).isEqualTo("ORD1");
        assertThat(vehicle.getId()).isEqualTo("VEH1");
    }
}
