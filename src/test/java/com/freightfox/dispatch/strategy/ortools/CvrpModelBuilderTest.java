package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CvrpModelBuilderTest {

    private final TestHaversine haversine = new TestHaversine();
    private final CvrpModelBuilder builder = new CvrpModelBuilder(haversine);

    private final Vehicle v1 = new Vehicle("V1", 100, 28.7041, 77.1025, "KB");
    private final Vehicle v2 = new Vehicle("V2", 80, 28.5355, 77.3910, "N18");
    private final DeliveryOrder o1 = new DeliveryOrder("O1", 28.6139, 77.2090, "CP", 15, Priority.HIGH);
    private final DeliveryOrder o2 = new DeliveryOrder("O2", 28.4595, 77.0266, "CH", 30, Priority.LOW);

    @Test
    void layoutIsVehicleStartsThenOrdersThenOneSharedEnd() {
        CvrpModel model = builder.build(List.of(o1, o2), List.of(v1, v2));

        assertThat(model.numNodes()).isEqualTo(5);
        assertThat(model.startNodes()).containsExactly(0, 1);
        assertThat(model.endNodes()).containsExactly(4, 4);
        assertThat(model.endNode()).isEqualTo(4);
        assertThat(model.orderNode(0)).isEqualTo(2);
        assertThat(model.orderIndex(3)).isEqualTo(1);
        assertThat(model.isOrderNode(1)).isFalse();
        assertThat(model.isOrderNode(2)).isTrue();
        assertThat(model.isOrderNode(4)).isFalse();
        assertThat(model.orders()).containsExactly(o1, o2);
        assertThat(model.vehicles()).containsExactly(v1, v2);
    }

    @Test
    void matrixIsWholeMetresSymmetricAndFreeToAndFromTheDummyEnd() {
        CvrpModel model = builder.build(List.of(o1, o2), List.of(v1, v2));

        long expected = Math.round(haversine.distanceKm(v1.getCurrentLatitude(), v1.getCurrentLongitude(),
                o1.getLatitude(), o1.getLongitude()) * 1000);
        assertThat(model.cost(0, 2)).isEqualTo(expected).isPositive();
        for (int i = 0; i < model.numNodes(); i++) {
            assertThat(model.cost(i, i)).isZero();
            assertThat(model.cost(i, model.endNode())).isZero();
            assertThat(model.cost(model.endNode(), i)).isZero();
            for (int j = 0; j < model.numNodes(); j++) {
                assertThat(model.cost(i, j)).isEqualTo(model.cost(j, i));
            }
        }
        assertThat(model.penalties().costUnitMetres()).isEqualTo(1);
    }

    @Test
    void demandsAreWeightsOnOrderNodesOnlyAndCapacitiesFollowVehicles() {
        CvrpModel model = builder.build(List.of(o1, o2), List.of(v1, v2));

        assertThat(model.demand(0)).isZero();
        assertThat(model.demand(1)).isZero();
        assertThat(model.demand(2)).isEqualTo(15);
        assertThat(model.demand(3)).isEqualTo(30);
        assertThat(model.demand(4)).isZero();
        assertThat(model.capacities()).containsExactly(100, 80);
    }

    @Test
    void penaltiesAreDerivedFromTheModel() {
        CvrpModel model = builder.build(List.of(o1, o2), List.of(v1, v2));

        long maxArc = 0;
        for (int i = 0; i < model.numNodes(); i++) {
            for (int j = 0; j < model.numNodes(); j++) {
                maxArc = Math.max(maxArc, model.cost(i, j));
            }
        }
        assertThat(model.penalties()).isEqualTo(DropPenalties.compute(maxArc, 2, 2, 1, 0, 1));
    }
}
