package com.freightfox.dispatch.strategy.registry;

import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.exception.BadRequestException;
import com.freightfox.dispatch.exception.UnknownStrategyException;
import com.freightfox.dispatch.strategy.DispatchResult;
import com.freightfox.dispatch.strategy.DispatchStrategy;
import com.freightfox.dispatch.strategy.StrategyNames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DispatchStrategyRegistryTest {

    private record NamedStrategy(String name) implements DispatchStrategy {
        @Override
        public DispatchResult plan(List<DeliveryOrder> orders, List<Vehicle> vehicles) {
            return new DispatchResult(name, List.of(), List.of());
        }
    }

    private final DispatchStrategy greedy = new NamedStrategy(StrategyNames.GREEDY);
    private final DispatchStrategy ortools = new NamedStrategy(StrategyNames.ORTOOLS);
    private final DispatchStrategyRegistry registry = new DispatchStrategyRegistry(List.of(ortools, greedy));

    @Test
    void nullOrBlankSelectsTheDefaultStrategy() {
        assertThat(registry.get(null)).isSameAs(greedy);
        assertThat(registry.get("")).isSameAs(greedy);
        assertThat(registry.get("   ")).isSameAs(greedy);
    }

    @Test
    void lookupIsCaseInsensitiveAndIgnoresSurroundingWhitespace() {
        assertThat(registry.get("greedy")).isSameAs(greedy);
        assertThat(registry.get("OrTools")).isSameAs(ortools);
        assertThat(registry.get(" ORTOOLS ")).isSameAs(ortools);
    }

    @Test
    void unknownNameListsTheSortedAllowedValues() {
        assertThatThrownBy(() -> registry.get("xyz"))
                .isInstanceOf(UnknownStrategyException.class)
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unknown strategy 'xyz'. Allowed values: greedy, ortools")
                .satisfies(ex -> assertThat(((BadRequestException) ex).getErrors())
                        .containsExactly("strategy: must be one of greedy, ortools"));
    }

    @Test
    void availableNamesAreSortedLowerCaseAndReadOnly() {
        DispatchStrategyRegistry mixedCase = new DispatchStrategyRegistry(
                List.of(new NamedStrategy("Zeta"), new NamedStrategy("ALPHA")));

        assertThat(mixedCase.availableNames()).containsExactly("alpha", "zeta");
        assertThatThrownBy(() -> mixedCase.availableNames().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void duplicateNamesFailAtStartup() {
        List<DispatchStrategy> duplicates = List.of(greedy, new NamedStrategy("GREEDY"));

        assertThatThrownBy(() -> new DispatchStrategyRegistry(duplicates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate dispatch strategy name 'greedy'");
    }

    @Test
    void worksWithNoStrategiesAtAll() {
        DispatchStrategyRegistry empty = new DispatchStrategyRegistry(List.of());

        assertThat(empty.availableNames()).isEmpty();
        assertThatThrownBy(() -> empty.get(null))
                .isInstanceOf(UnknownStrategyException.class)
                .hasMessage("Unknown strategy 'greedy'. Allowed values: none");
    }

    @Test
    void worksWhenOnlySomeStrategiesAreRegistered() {
        DispatchStrategyRegistry onlyGreedy = new DispatchStrategyRegistry(List.of(greedy));

        assertThat(onlyGreedy.get(null)).isSameAs(greedy);
        assertThatThrownBy(() -> onlyGreedy.get("ortools"))
                .hasMessage("Unknown strategy 'ortools'. Allowed values: greedy");
    }

    @Test
    void echoedNameIsTruncatedWhenVeryLong() {
        String longName = "x".repeat(500);

        assertThatThrownBy(() -> registry.get(longName))
                .hasMessage("Unknown strategy '" + "x".repeat(64) + "...'. Allowed values: greedy, ortools");
    }
}
