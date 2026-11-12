package com.freightfox.dispatch.controller;

/** Paths, parameter names and success messages of the dispatch REST API. */
public final class ApiMessages {

    public static final String BASE_PATH = "/api/dispatch";
    public static final String ORDERS_PATH = "/orders";
    public static final String VEHICLES_PATH = "/vehicles";
    public static final String PLAN_PATH = "/plan";
    public static final String STRATEGY_PARAM = "strategy";

    public static final String TAG = "Dispatch";
    public static final String TAG_DESCRIPTION =
            "Upload delivery orders and vehicles, then compute an optimized dispatch plan";

    public static final String ORDERS_ACCEPTED = "Delivery orders accepted.";
    public static final String VEHICLES_ACCEPTED = "Vehicle details accepted.";
    public static final String ORDERS_DELETED = "All delivery orders deleted.";
    public static final String VEHICLES_DELETED = "All vehicles deleted.";

    private ApiMessages() {
    }
}
