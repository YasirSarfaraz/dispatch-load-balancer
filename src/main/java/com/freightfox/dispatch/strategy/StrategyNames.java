package com.freightfox.dispatch.strategy;

/** Names of the dispatch strategies. Also used as Spring bean names and as the ?strategy= value. */
public final class StrategyNames {

    public static final String GREEDY = "greedy";
    public static final String ORTOOLS = "ortools";
    public static final String DEFAULT = GREEDY;

    private StrategyNames() {
    }
}
