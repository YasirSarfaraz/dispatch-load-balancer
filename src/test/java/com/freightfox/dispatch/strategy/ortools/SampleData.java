package com.freightfox.dispatch.strategy.ortools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Reads the section 9 sample dataset from src/test/resources/data. */
final class SampleData {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SampleData() {
    }

    static List<DeliveryOrder> orders() {
        List<DeliveryOrder> orders = new ArrayList<>();
        for (JsonNode n : read("/data/sample-orders.json").get("orders")) {
            orders.add(new DeliveryOrder(n.get("orderId").asText(), n.get("latitude").asDouble(),
                    n.get("longitude").asDouble(), n.get("address").asText(), n.get("packageWeight").asInt(),
                    Priority.valueOf(n.get("priority").asText())));
        }
        return orders;
    }

    static List<Vehicle> vehicles() {
        List<Vehicle> vehicles = new ArrayList<>();
        for (JsonNode n : read("/data/sample-vehicles.json").get("vehicles")) {
            vehicles.add(new Vehicle(n.get("vehicleId").asText(), n.get("capacity").asInt(),
                    n.get("currentLatitude").asDouble(), n.get("currentLongitude").asDouble(),
                    n.get("currentAddress").asText()));
        }
        return vehicles;
    }

    private static JsonNode read(String resource) {
        try (InputStream in = SampleData.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + resource);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
