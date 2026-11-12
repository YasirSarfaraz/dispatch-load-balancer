package com.freightfox.dispatch.strategy.registry;

import com.freightfox.dispatch.exception.UnknownStrategyException;
import com.freightfox.dispatch.strategy.DispatchStrategy;
import com.freightfox.dispatch.strategy.StrategyNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Looks up a {@link DispatchStrategy} by name (Strategy + Registry pattern). Built from every
 * {@code DispatchStrategy} bean, so adding an algorithm only needs a new bean and no if/else
 * chain anywhere. Names are case-insensitive. Works with any number of strategies, including none.
 */
@Component
public class DispatchStrategyRegistry {

    private static final Logger log = LoggerFactory.getLogger(DispatchStrategyRegistry.class);

    private final Map<String, DispatchStrategy> strategies;
    private final SortedSet<String> names;

    /**
     * @param strategies every strategy bean (may be empty)
     * @throws IllegalStateException if two strategies share a name (ignoring case)
     */
    public DispatchStrategyRegistry(List<DispatchStrategy> strategies) {
        SortedMap<String, DispatchStrategy> byName = new TreeMap<>();
        for (DispatchStrategy strategy : strategies) {
            String key = normalize(strategy.name());
            DispatchStrategy previous = byName.putIfAbsent(key, strategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate dispatch strategy name '" + key + "': "
                        + previous.getClass().getName() + " and " + strategy.getClass().getName());
            }
        }
        this.strategies = Collections.unmodifiableMap(byName);
        this.names = Collections.unmodifiableSortedSet(new TreeSet<>(byName.keySet()));
        log.info("Registered dispatch strategies: {}", this.strategies.keySet());
    }

    /**
     * @param name strategy name, case-insensitive; null or blank selects {@link StrategyNames#DEFAULT}
     * @return the strategy registered under that name
     * @throws UnknownStrategyException if no strategy has that name
     */
    public DispatchStrategy get(String name) {
        String key = name == null || name.isBlank() ? StrategyNames.DEFAULT : normalize(name);
        DispatchStrategy strategy = strategies.get(key);
        if (strategy == null) {
            throw new UnknownStrategyException(name == null || name.isBlank() ? key : name.strip(), availableNames());
        }
        return strategy;
    }

    /** Registered strategy names (lower case), sorted alphabetically. */
    public SortedSet<String> availableNames() {
        return names;
    }

    private static String normalize(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
