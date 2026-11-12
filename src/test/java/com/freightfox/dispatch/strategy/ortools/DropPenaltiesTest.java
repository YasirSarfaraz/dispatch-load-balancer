package com.freightfox.dispatch.strategy.ortools;

import com.freightfox.dispatch.entity.Priority;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DropPenaltiesTest {

    @Test
    void penaltiesFollowTheDocumentedFormula() {
        // maxArc 1,000 m, 3 orders + 2 vehicles -> routeBound 5,000
        DropPenalties p = DropPenalties.compute(1_000, 3, 2, 1, 1, 1);

        assertThat(p.costUnitMetres()).isEqualTo(1);
        assertThat(p.low()).isEqualTo(5_001);
        assertThat(p.medium()).isEqualTo(5_001 + 5_000 + 1);
        assertThat(p.high()).isEqualTo(10_002 + 5_001 + 5_000 + 1);
    }

    @Test
    void penaltiesAreLexicographic() {
        int nH = 12;
        int nM = 8;
        int nL = 10;
        long maxArc = 30_000;
        int vehicles = 5;
        long routeBound = maxArc * (nH + nM + nL + vehicles);

        DropPenalties p = DropPenalties.compute(maxArc, nH + nM + nL, vehicles, nH, nM, nL);

        assertThat(p.low()).isGreaterThan(routeBound);
        assertThat(p.medium()).isGreaterThan(p.low() * nL + routeBound);
        assertThat(p.high()).isGreaterThan(p.medium() * nM + p.low() * nL + routeBound);
        assertThat(p.forPriority(Priority.HIGH)).isEqualTo(p.high());
        assertThat(p.forPriority(Priority.MEDIUM)).isEqualTo(p.medium());
        assertThat(p.forPriority(Priority.LOW)).isEqualTo(p.low());
    }

    @Test
    void identicalLocationsStillGivePositiveOrderedPenalties() {
        DropPenalties p = DropPenalties.compute(0, 2, 1, 1, 0, 1);

        assertThat(p.low()).isEqualTo(1);
        assertThat(p.medium()).isEqualTo(2);
        assertThat(p.high()).isEqualTo(2);
        assertThat(p.high()).isGreaterThan(p.low());
    }

    @Test
    void defaultCapWithGlobalDistancesFitsInMetres() {
        // 1,000 orders (333/333/334), 2,000 vehicles, half the Earth's circumference as the max arc
        DropPenalties p = DropPenalties.compute(20_015_000, 1_000, 2_000, 333, 333, 334);

        assertThat(p.costUnitMetres()).isEqualTo(1);
        assertThat(Math.multiplyExact(p.high(), 333L)).isLessThan(DropPenalties.OBJECTIVE_BUDGET);
    }

    @Test
    void coarsensTheCostUnitInsteadOfOverflowing() {
        DropPenalties p = DropPenalties.compute(20_015_000, 3_000, 2_000, 1_000, 1_000, 1_000);

        assertThat(p.costUnitMetres()).isGreaterThan(1);
        long routeBound = p.toCostUnits(20_015_000) * 5_000;
        long worst = p.high() * 1_000 + p.medium() * 1_000 + p.low() * 1_000 + routeBound;
        assertThat(worst).isPositive().isLessThanOrEqualTo(DropPenalties.OBJECTIVE_BUDGET);
        assertThat(p.high()).isGreaterThan(p.medium() * 1_000 + p.low() * 1_000 + routeBound);
    }

    @Test
    void toCostUnitsRounds() {
        DropPenalties metres = new DropPenalties(1, 1, 2, 3);
        DropPenalties tens = new DropPenalties(10, 1, 2, 3);

        assertThat(metres.toCostUnits(1_234)).isEqualTo(1_234);
        assertThat(tens.toCostUnits(1_234)).isEqualTo(123);
        assertThat(tens.toCostUnits(1_235)).isEqualTo(124);
    }

    @Test
    void impossiblyLargeModelsAreRejected() {
        assertThatThrownBy(() -> DropPenalties.compute(20_015_000, 3_000_000, 1, 1_000_000, 1_000_000, 1_000_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void negativeInputsAreRejected() {
        assertThatThrownBy(() -> DropPenalties.compute(-1, 1, 1, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
