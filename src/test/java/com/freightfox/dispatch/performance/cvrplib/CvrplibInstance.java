package com.freightfox.dispatch.performance.cvrplib;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A CVRPLIB instance in TSPLIB format ({@code .vrp}) with {@code EDGE_WEIGHT_TYPE: EUC_2D} and a
 * single depot, as used by the Uchoa et al. "X" set.
 *
 * @param name      instance name, e.g. X-n101-k25
 * @param capacity  vehicle capacity Q
 * @param k         k from the name: the minimum number of routes
 * @param depot     the depot node
 * @param customers every other node, in file order
 */
public record CvrplibInstance(String name, int capacity, int k, Node depot, List<Node> customers) {

    private static final Pattern K_IN_NAME = Pattern.compile("-k(\\d+)");
    private static final String KEY_NAME = "NAME";
    private static final String KEY_DIMENSION = "DIMENSION";
    private static final String KEY_CAPACITY = "CAPACITY";
    private static final String KEY_EDGE_WEIGHT_TYPE = "EDGE_WEIGHT_TYPE";
    private static final String EUC_2D = "EUC_2D";
    private static final String NODE_COORD_SECTION = "NODE_COORD_SECTION";
    private static final String DEMAND_SECTION = "DEMAND_SECTION";
    private static final String DEPOT_SECTION = "DEPOT_SECTION";
    private static final String EOF = "EOF";
    private static final String END_OF_DEPOTS = "-1";

    public CvrplibInstance {
        customers = List.copyOf(customers);
    }

    /**
     * A node of the instance.
     *
     * @param id     node number in the .vrp file (1-based; the depot is usually 1)
     * @param x      x coordinate
     * @param y      y coordinate
     * @param demand demand (0 for the depot)
     */
    public record Node(int id, double x, double y, int demand) {
    }

    /** Parses a .vrp file. */
    public static CvrplibInstance parse(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        Map<String, String> header = new HashMap<>();
        Map<Integer, double[]> coordinates = new HashMap<>();
        Map<Integer, Integer> demands = new HashMap<>();
        List<Integer> depots = new ArrayList<>();
        String section = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.equals(EOF)) {
                    break;
                }
                if (trimmed.equals(NODE_COORD_SECTION) || trimmed.equals(DEMAND_SECTION)
                        || trimmed.equals(DEPOT_SECTION)) {
                    section = trimmed;
                    continue;
                }
                if (section == null) {
                    int colon = trimmed.indexOf(':');
                    if (colon > 0) {
                        header.put(trimmed.substring(0, colon).strip().toUpperCase(Locale.ROOT),
                                trimmed.substring(colon + 1).strip().replace("\"", ""));
                    }
                    continue;
                }
                String[] fields = trimmed.split("\\s+");
                switch (section) {
                    case NODE_COORD_SECTION -> coordinates.put(Integer.parseInt(fields[0]),
                            new double[]{Double.parseDouble(fields[1]), Double.parseDouble(fields[2])});
                    case DEMAND_SECTION -> demands.put(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]));
                    case DEPOT_SECTION -> {
                        if (!fields[0].equals(END_OF_DEPOTS)) {
                            depots.add(Integer.parseInt(fields[0]));
                        }
                    }
                    default -> throw new IllegalStateException("Unexpected section " + section);
                }
            }
        }
        return build(header, coordinates, demands, depots);
    }

    private static CvrplibInstance build(Map<String, String> header, Map<Integer, double[]> coordinates,
                                         Map<Integer, Integer> demands, List<Integer> depots) {
        String name = require(header, KEY_NAME);
        if (!EUC_2D.equals(require(header, KEY_EDGE_WEIGHT_TYPE))) {
            throw new IllegalArgumentException(name + ": only EUC_2D is supported");
        }
        int dimension = Integer.parseInt(require(header, KEY_DIMENSION));
        int capacity = Integer.parseInt(require(header, KEY_CAPACITY));
        if (depots.size() != 1) {
            throw new IllegalArgumentException(name + ": exactly one depot expected, got " + depots);
        }
        if (coordinates.size() != dimension || demands.size() != dimension) {
            throw new IllegalArgumentException(name + ": expected " + dimension + " nodes, got "
                    + coordinates.size() + " coordinates and " + demands.size() + " demands");
        }
        Matcher matcher = K_IN_NAME.matcher(name);
        if (!matcher.find()) {
            throw new IllegalArgumentException(name + ": no -k<number> in the name");
        }
        int depotId = depots.get(0);
        Node depot = null;
        List<Node> customers = new ArrayList<>(dimension - 1);
        for (int id = 1; id <= dimension; id++) {
            double[] xy = Objects.requireNonNull(coordinates.get(id), "coordinates of node " + id);
            Node node = new Node(id, xy[0], xy[1], Objects.requireNonNull(demands.get(id), "demand of node " + id));
            if (id == depotId) {
                depot = node;
            } else {
                customers.add(node);
            }
        }
        return new CvrplibInstance(name, capacity, Integer.parseInt(matcher.group(1)), depot, customers);
    }

    private static String require(Map<String, String> header, String key) {
        String value = header.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing header " + key);
        }
        return value;
    }

    /** Total demand of all customers. */
    public long totalDemand() {
        return customers.stream().mapToLong(Node::demand).sum();
    }
}
