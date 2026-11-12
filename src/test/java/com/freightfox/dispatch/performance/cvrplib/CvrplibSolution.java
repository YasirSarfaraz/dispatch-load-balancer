package com.freightfox.dispatch.performance.cvrplib;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A CVRPLIB solution file ({@code .sol}): lines {@code Route #i: c1 c2 ...} and {@code Cost N}.
 *
 * <p>CVRPLIB numbers customers in .sol files from 1 with the depot as 0, i.e. customer {@code c}
 * is node {@code c + 1} of the .vrp file when the depot is node 1. {@link #nodeId(int)} applies
 * that mapping; the benchmark test checks it by re-computing the published cost exactly.
 *
 * @param routes customer numbers per route, in visiting order (depot not listed)
 * @param cost   published cost
 */
public record CvrplibSolution(List<List<Integer>> routes, long cost) {

    private static final String ROUTE_PREFIX = "Route";
    private static final String COST_PREFIX = "Cost";

    public CvrplibSolution {
        routes = routes.stream().map(List::copyOf).toList();
    }

    /** The .vrp node id of a customer number from a .sol file. */
    public static int nodeId(int customerNumber) {
        return customerNumber + 1;
    }

    /** Parses a .sol file. */
    public static CvrplibSolution parse(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        List<List<Integer>> routes = new ArrayList<>();
        Long cost = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.startsWith(ROUTE_PREFIX)) {
                    String customers = trimmed.substring(trimmed.indexOf(':') + 1).strip();
                    routes.add(customers.isEmpty() ? List.of()
                            : Arrays.stream(customers.split("\\s+")).map(Integer::valueOf).toList());
                } else if (trimmed.startsWith(COST_PREFIX)) {
                    cost = Math.round(Double.parseDouble(trimmed.substring(COST_PREFIX.length()).strip()));
                }
            }
        }
        if (cost == null || routes.isEmpty()) {
            throw new IllegalArgumentException("Solution file without routes or cost");
        }
        return new CvrplibSolution(routes, cost);
    }
}
