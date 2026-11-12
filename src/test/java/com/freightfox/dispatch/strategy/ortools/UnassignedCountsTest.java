package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.StrategyNames;
import com.freightfox.dispatch.strategy.UnassignedOrder;
import com.freightfox.dispatch.strategy.UnassignmentReasons;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnassignedCountsTest {

    @Test
    void countsEveryUnassignedOrderByPriority() {
        DispatchResult plan = new DispatchResult(StrategyNames.ORTOOLS, List.of(), List.of(
                unassigned("H1", Priority.HIGH, UnassignmentReasons.EXCEEDS_EVERY_VEHICLE),
                unassigned("M1", Priority.MEDIUM, UnassignmentReasons.DROPPED_BY_OPTIMIZER),
                unassigned("M2", Priority.MEDIUM, UnassignmentReasons.DROPPED_BY_OPTIMIZER),
                unassigned("L1", Priority.LOW, UnassignmentReasons.DROPPED_BY_OPTIMIZER),
                unassigned("L2", Priority.LOW, UnassignmentReasons.DROPPED_BY_OPTIMIZER),
                unassigned("L3", Priority.LOW, UnassignmentReasons.DROPPED_BY_OPTIMIZER)));

        UnassignedCounts counts = UnassignedCounts.of(plan);

        assertThat(counts).isEqualTo(new UnassignedCounts(1, 2, 3));
        assertThat(counts).hasToString("1/2/3");
    }

    @Test
    void comparisonIsLexicographicHighThenMediumThenLow() {
        UnassignedCounts base = new UnassignedCounts(1, 5, 5);

        // one more HIGH dropped is worse even with far fewer MEDIUM and LOW dropped
        assertThat(new UnassignedCounts(2, 0, 0).isWorseThan(base)).isTrue();
        // fewer HIGH dropped is better even with many more MEDIUM and LOW dropped
        assertThat(new UnassignedCounts(0, 99, 99).isWorseThan(base)).isFalse();
        // HIGH tied: MEDIUM decides
        assertThat(new UnassignedCounts(1, 6, 0).isWorseThan(base)).isTrue();
        assertThat(new UnassignedCounts(1, 4, 99).isWorseThan(base)).isFalse();
        // HIGH and MEDIUM tied: LOW decides
        assertThat(new UnassignedCounts(1, 5, 6).isWorseThan(base)).isTrue();
        assertThat(new UnassignedCounts(1, 5, 4).isWorseThan(base)).isFalse();
        // equal is not worse
        assertThat(new UnassignedCounts(1, 5, 5).isWorseThan(base)).isFalse();
    }

    private static UnassignedOrder unassigned(String id, Priority priority, String reason) {
        return new UnassignedOrder(new DeliveryOrder(id, 0, 0, "Address " + id, 1, priority), reason);
    }
}
