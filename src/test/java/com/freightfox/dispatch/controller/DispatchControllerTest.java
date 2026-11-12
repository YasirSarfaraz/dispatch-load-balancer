package com.freightfox.dispatch.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freightfox.dispatch.dto.response.DispatchPlanResponse;
import com.freightfox.dispatch.dto.response.OrderResponse;
import com.freightfox.dispatch.dto.response.UnassignedOrderResponse;
import com.freightfox.dispatch.dto.response.VehiclePlanResponse;
import com.freightfox.dispatch.entity.DeliveryOrder;
import com.freightfox.dispatch.entity.Priority;
import com.freightfox.dispatch.entity.Vehicle;
import com.freightfox.dispatch.exception.DuplicateIdException;
import com.freightfox.dispatch.exception.NoVehiclesAvailableException;
import com.freightfox.dispatch.exception.UnknownStrategyException;
import com.freightfox.dispatch.mapper.RequestMapper;
import com.freightfox.dispatch.service.DispatchService;
import com.freightfox.dispatch.service.OrderService;
import com.freightfox.dispatch.service.UpsertResult;
import com.freightfox.dispatch.service.VehicleService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.freightfox.dispatch.controller.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DispatchController.class)
@Import(RequestMapper.class)
class DispatchControllerTest {

    private static final String ORDERS = "/api/dispatch/orders";
    private static final String VEHICLES = "/api/dispatch/vehicles";
    private static final String PLAN = "/api/dispatch/plan";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private VehicleService vehicleService;
    @MockitoBean
    private DispatchService dispatchService;

    // ----------------------------------------------------------------- request builders

    static Map<String, Object> validOrder(String id) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", id);
        order.put("latitude", 12.9716);
        order.put("longitude", 77.5946);
        order.put("address", "MG Road, Bangalore, Karnataka, India");
        order.put("packageWeight", 10);
        order.put("priority", "HIGH");
        return order;
    }

    static Map<String, Object> validVehicle(String id) {
        Map<String, Object> vehicle = new LinkedHashMap<>();
        vehicle.put("vehicleId", id);
        vehicle.put("capacity", 100);
        vehicle.put("currentLatitude", 12.9716);
        vehicle.put("currentLongitude", 77.6413);
        vehicle.put("currentAddress", "Indiranagar, Bangalore, Karnataka, India");
        return vehicle;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String ordersBody(Object... orders) throws Exception {
        return json(Map.of("orders", Arrays.asList(orders)));
    }

    private String vehiclesBody(Object... vehicles) throws Exception {
        return json(Map.of("vehicles", Arrays.asList(vehicles)));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult postJson(String path, String body) throws Exception {
        return perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ----------------------------------------------------------------- POST /orders

    @Nested
    class PostOrders {

        @Test
        void acceptsTheAssignmentExampleWithTheExactBody() throws Exception {
            when(orderService.upsertOrders(anyList())).thenReturn(new UpsertResult(2, 0));

            mockMvc.perform(post(ORDERS).contentType(MediaType.APPLICATION_JSON).content(OpenApiExamples.ORDERS_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().json("{\"message\": \"Delivery orders accepted.\", \"status\": \"success\"}",
                            JsonCompareMode.STRICT));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DeliveryOrder>> captor = ArgumentCaptor.forClass(List.class);
            verify(orderService).upsertOrders(captor.capture());
            assertThat(captor.getValue()).extracting(DeliveryOrder::getOrderId).containsExactly("ORD001", "ORD002");
            DeliveryOrder second = captor.getValue().get(1);
            assertThat(second.getLatitude()).isEqualTo(13.0827);
            assertThat(second.getLongitude()).isEqualTo(80.2707);
            assertThat(second.getAddress()).isEqualTo("Anna Salai, Chennai, Tamil Nadu, India");
            assertThat(second.getPackageWeight()).isEqualTo(20);
            assertThat(second.getPriority()).isEqualTo(Priority.MEDIUM);
        }

        @Test
        void acceptsTheSampleDatasetExample() throws Exception {
            mockMvc.perform(post(ORDERS).contentType(MediaType.APPLICATION_JSON)
                            .content(OpenApiExamples.ORDERS_SAMPLE_DATASET))
                    .andExpect(status().isOk());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DeliveryOrder>> captor = ArgumentCaptor.forClass(List.class);
            verify(orderService).upsertOrders(captor.capture());
            assertThat(captor.getValue()).hasSize(30);
        }

        @ParameterizedTest(name = "{0} = {1}")
        @MethodSource("com.freightfox.dispatch.controller.DispatchControllerTest#invalidOrderFields")
        void rejectsAnInvalidField(String field, Object value, String expectedError) throws Exception {
            Map<String, Object> order = validOrder("ORD001");
            order.put(field, value);

            List<String> errors = assertError(postJson(ORDERS, ordersBody(order)), 400, "Validation failed");

            assertThat(errors).containsExactly(expectedError);
            verifyNoInteractions(orderService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"orderId", "latitude", "longitude", "address", "packageWeight", "priority"})
        void rejectsAMissingField(String field) throws Exception {
            Map<String, Object> order = validOrder("ORD001");
            order.remove(field);
            String reason = field.equals("orderId") || field.equals("address") ? "must not be blank" : "must not be null";

            List<String> errors = assertError(postJson(ORDERS, ordersBody(order)), 400, "Validation failed");

            assertThat(errors).containsExactly("orders[0]." + field + ": " + reason);
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"orders\": []}", "{\"orders\": null}"})
        void rejectsAMissingOrEmptyList(String body) throws Exception {
            List<String> errors = assertError(postJson(ORDERS, body), 400, "Validation failed");

            assertThat(errors).containsExactly("orders: must not be empty");
        }

        @Test
        void rejectsANullListElement() throws Exception {
            List<String> errors = assertError(postJson(ORDERS, ordersBody(validOrder("ORD001"), null)),
                    400, "Validation failed");

            assertThat(errors).containsExactly("orders[1]: must not be null");
        }

        @Test
        void reportsEveryErrorSorted() throws Exception {
            Map<String, Object> first = validOrder("ORD001");
            first.put("packageWeight", 0);
            first.put("latitude", 91);
            Map<String, Object> second = validOrder(" ");
            second.put("longitude", -181);

            List<String> errors = assertError(postJson(ORDERS, ordersBody(first, second)), 400, "Validation failed");

            assertThat(errors).containsExactly(
                    "orders[0].latitude: must be less than or equal to 90.0",
                    "orders[0].packageWeight: must be greater than 0",
                    "orders[1].longitude: must be greater than or equal to -180.0",
                    "orders[1].orderId: must not be blank");
        }

        @Test
        void rejectsAnUnknownPriorityListingTheAllowedValues() throws Exception {
            Map<String, Object> order = validOrder("ORD001");
            order.put("priority", "URGENT");

            List<String> errors = assertError(postJson(ORDERS, ordersBody(order)), 400,
                    "Invalid value 'URGENT' for field 'orders[0].priority'. Allowed values: HIGH, MEDIUM, LOW");

            assertThat(errors).containsExactly("orders[0].priority: must be one of HIGH, MEDIUM, LOW");
        }

        @Test
        void acceptsPriorityInAnyCase() throws Exception {
            Map<String, Object> order = validOrder("ORD001");
            order.put("priority", "low");

            mockMvc.perform(post(ORDERS).contentType(MediaType.APPLICATION_JSON).content(ordersBody(order)))
                    .andExpect(status().isOk());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DeliveryOrder>> captor = ArgumentCaptor.forClass(List.class);
            verify(orderService).upsertOrders(captor.capture());
            assertThat(captor.getValue().get(0).getPriority()).isEqualTo(Priority.LOW);
        }

        @Test
        void rejectsTextForANumber() throws Exception {
            Map<String, Object> order = validOrder("ORD001");
            order.put("latitude", "abc");

            List<String> errors = assertError(postJson(ORDERS, ordersBody(order)), 400,
                    "Invalid value for field 'orders[0].latitude'. Expected type: number");

            assertThat(errors).containsExactly("orders[0].latitude: must be of type number");
        }

        @Test
        void rejectsADecimalForAnIntegerField() throws Exception {
            Map<String, Object> order = validOrder("ORD001");
            order.put("packageWeight", 10.5);

            List<String> errors = assertError(postJson(ORDERS, ordersBody(order)), 400,
                    "Invalid value '10.5' for field 'orders[0].packageWeight'. Expected type: integer");

            assertThat(errors).containsExactly("orders[0].packageWeight: must be of type integer");
        }

        @Test
        void rejectsAnObjectWhereTheListIsExpected() throws Exception {
            List<String> errors = assertError(postJson(ORDERS, "{\"orders\": \"ORD001\"}"), 400,
                    "Invalid value for field 'orders'. Expected type: array");

            assertThat(errors).containsExactly("orders: must be of type array");
        }

        @Test
        void rejectsAnArrayAsTheBody() throws Exception {
            List<String> errors = assertError(postJson(ORDERS, "[]"), 400,
                    "Invalid value for field 'body'. Expected type: object");

            assertThat(errors).containsExactly("body: must be of type object");
        }

        @ParameterizedTest
        @ValueSource(strings = {"{\"orders\": [", "{orders: []}", "not json", "{\"orders\": [{\"orderId\": \"A\",}]}"})
        void rejectsMalformedJson(String body) throws Exception {
            List<String> errors = assertError(postJson(ORDERS, body), 400, "Malformed JSON request");

            assertThat(errors).singleElement().asString().startsWith("body: invalid JSON at line 1, column ");
            verifyNoInteractions(orderService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "null"})
        void rejectsAMissingBody(String body) throws Exception {
            List<String> errors = assertError(postJson(ORDERS, body), 400, "Request body is missing or malformed");

            assertThat(errors).containsExactly("body: a JSON request body is required");
        }

        @Test
        void rejectsDuplicateIdsReportedByTheService() throws Exception {
            when(orderService.upsertOrders(anyList()))
                    .thenThrow(new DuplicateIdException("orderId", "orders", List.of("ORD001")));

            List<String> errors = assertError(postJson(ORDERS, ordersBody(validOrder("ORD001"), validOrder("ORD001"))),
                    400, "Duplicate orderId values in request");

            assertThat(errors).containsExactly("orders: duplicate orderId 'ORD001'");
        }

        @Test
        void rejectsANonJsonContentType() throws Exception {
            MvcResult result = perform(post(ORDERS).contentType(MediaType.TEXT_PLAIN).content("hello"));

            List<String> errors = assertError(result, 415, "Content type 'text/plain' is not supported");
            assertThat(errors).singleElement().asString().startsWith("supported content types: application/json");
        }

        @Test
        void capsTheNumberOfReportedErrors() throws Exception {
            List<Object> orders = new ArrayList<>();
            for (int i = 0; i < 150; i++) {
                Map<String, Object> order = validOrder("ORD" + i);
                order.put("packageWeight", 0);
                orders.add(order);
            }

            List<String> errors = assertError(postJson(ORDERS, json(Map.of("orders", orders))), 400,
                    "Validation failed");

            assertThat(errors).hasSize(101).last().isEqualTo("... and 50 more errors");
        }
    }

    static Stream<Arguments> invalidOrderFields() {
        return Stream.of(
                Arguments.of("orderId", "", "orders[0].orderId: must not be blank"),
                Arguments.of("orderId", "   ", "orders[0].orderId: must not be blank"),
                Arguments.of("orderId", "X".repeat(65), "orders[0].orderId: size must be between 0 and 64"),
                Arguments.of("latitude", 91, "orders[0].latitude: must be less than or equal to 90.0"),
                Arguments.of("latitude", 90.0001, "orders[0].latitude: must be less than or equal to 90.0"),
                Arguments.of("latitude", -91, "orders[0].latitude: must be greater than or equal to -90.0"),
                Arguments.of("longitude", 181, "orders[0].longitude: must be less than or equal to 180.0"),
                Arguments.of("longitude", -181, "orders[0].longitude: must be greater than or equal to -180.0"),
                Arguments.of("packageWeight", 0, "orders[0].packageWeight: must be greater than 0"),
                Arguments.of("packageWeight", -5, "orders[0].packageWeight: must be greater than 0"),
                Arguments.of("address", "", "orders[0].address: must not be blank"),
                Arguments.of("address", " ", "orders[0].address: must not be blank"),
                Arguments.of("priority", null, "orders[0].priority: must not be null"),
                Arguments.of("latitude", null, "orders[0].latitude: must not be null"));
    }

    // ----------------------------------------------------------------- POST /vehicles

    @Nested
    class PostVehicles {

        @Test
        void acceptsTheAssignmentExampleWithTheExactBody() throws Exception {
            mockMvc.perform(post(VEHICLES).contentType(MediaType.APPLICATION_JSON).content(OpenApiExamples.VEHICLES_REQUEST))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"message\": \"Vehicle details accepted.\", \"status\": \"success\"}",
                            JsonCompareMode.STRICT));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Vehicle>> captor = ArgumentCaptor.forClass(List.class);
            verify(vehicleService).upsertVehicles(captor.capture());
            assertThat(captor.getValue()).extracting(Vehicle::getVehicleId).containsExactly("VEH001", "VEH002");
            assertThat(captor.getValue().get(1).getCapacity()).isEqualTo(150);
        }

        @Test
        void acceptsTheSampleDatasetExample() throws Exception {
            mockMvc.perform(post(VEHICLES).contentType(MediaType.APPLICATION_JSON)
                            .content(OpenApiExamples.VEHICLES_SAMPLE_DATASET))
                    .andExpect(status().isOk());
        }

        @ParameterizedTest(name = "{0} = {1}")
        @MethodSource("com.freightfox.dispatch.controller.DispatchControllerTest#invalidVehicleFields")
        void rejectsAnInvalidField(String field, Object value, String expectedError) throws Exception {
            Map<String, Object> vehicle = validVehicle("VEH001");
            vehicle.put(field, value);

            List<String> errors = assertError(postJson(VEHICLES, vehiclesBody(vehicle)), 400, "Validation failed");

            assertThat(errors).containsExactly(expectedError);
            verifyNoInteractions(vehicleService);
        }

        @ParameterizedTest
        @ValueSource(strings = {"vehicleId", "capacity", "currentLatitude", "currentLongitude", "currentAddress"})
        void rejectsAMissingField(String field) throws Exception {
            Map<String, Object> vehicle = validVehicle("VEH001");
            vehicle.remove(field);
            String reason = field.equals("vehicleId") || field.equals("currentAddress")
                    ? "must not be blank" : "must not be null";

            List<String> errors = assertError(postJson(VEHICLES, vehiclesBody(vehicle)), 400, "Validation failed");

            assertThat(errors).containsExactly("vehicles[0]." + field + ": " + reason);
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"vehicles\": []}"})
        void rejectsAMissingOrEmptyList(String body) throws Exception {
            assertThat(assertError(postJson(VEHICLES, body), 400, "Validation failed"))
                    .containsExactly("vehicles: must not be empty");
        }

        @Test
        void rejectsANullListElement() throws Exception {
            assertThat(assertError(postJson(VEHICLES, vehiclesBody((Object) null)), 400, "Validation failed"))
                    .containsExactly("vehicles[0]: must not be null");
        }

        @Test
        void rejectsADecimalCapacity() throws Exception {
            Map<String, Object> vehicle = validVehicle("VEH001");
            vehicle.put("capacity", 10.5);

            assertThat(assertError(postJson(VEHICLES, vehiclesBody(vehicle)), 400,
                    "Invalid value '10.5' for field 'vehicles[0].capacity'. Expected type: integer"))
                    .containsExactly("vehicles[0].capacity: must be of type integer");
        }

        @Test
        void rejectsMalformedJson() throws Exception {
            assertError(postJson(VEHICLES, "{\"vehicles\": [{]}"), 400, "Malformed JSON request");
        }

        @Test
        void rejectsDuplicateIdsReportedByTheService() throws Exception {
            when(vehicleService.upsertVehicles(anyList()))
                    .thenThrow(new DuplicateIdException("vehicleId", "vehicles", List.of("VEH001")));

            assertThat(assertError(postJson(VEHICLES, vehiclesBody(validVehicle("VEH001"), validVehicle("VEH001"))),
                    400, "Duplicate vehicleId values in request"))
                    .containsExactly("vehicles: duplicate vehicleId 'VEH001'");
        }
    }

    static Stream<Arguments> invalidVehicleFields() {
        return Stream.of(
                Arguments.of("vehicleId", "", "vehicles[0].vehicleId: must not be blank"),
                Arguments.of("capacity", 0, "vehicles[0].capacity: must be greater than 0"),
                Arguments.of("capacity", -10, "vehicles[0].capacity: must be greater than 0"),
                Arguments.of("currentLatitude", 91, "vehicles[0].currentLatitude: must be less than or equal to 90.0"),
                Arguments.of("currentLatitude", -91,
                        "vehicles[0].currentLatitude: must be greater than or equal to -90.0"),
                Arguments.of("currentLongitude", 181,
                        "vehicles[0].currentLongitude: must be less than or equal to 180.0"),
                Arguments.of("currentLongitude", -181,
                        "vehicles[0].currentLongitude: must be greater than or equal to -180.0"),
                Arguments.of("currentAddress", "", "vehicles[0].currentAddress: must not be blank"));
    }

    // ----------------------------------------------------------------- GET /plan

    @Nested
    class GetPlan {

        @Test
        void returnsThePlanUsingTheDefaultStrategyWhenNoneIsGiven() throws Exception {
            DispatchPlanResponse plan = new DispatchPlanResponse(
                    List.of(new VehiclePlanResponse("VEH001", 10, "5.06 km", List.of(new OrderResponse(
                            "ORD001", 12.9716, 77.5946, "MG Road, Bangalore, Karnataka, India", 10, Priority.HIGH))),
                            new VehiclePlanResponse("VEH002", 0, "0.00 km", List.of())),
                    List.of(new UnassignedOrderResponse("ORD002", 13.0827, 80.2707,
                            "Anna Salai, Chennai, Tamil Nadu, India", 500, Priority.LOW,
                            "Package weight exceeds capacity of every vehicle")),
                    "greedy");
            when(dispatchService.generatePlan(isNull())).thenReturn(plan);

            mockMvc.perform(get(PLAN))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            {
                              "dispatchPlan": [
                                {"vehicleId": "VEH001", "totalLoad": 10, "totalDistance": "5.06 km",
                                 "assignedOrders": [
                                   {"orderId": "ORD001", "latitude": 12.9716, "longitude": 77.5946,
                                    "address": "MG Road, Bangalore, Karnataka, India", "packageWeight": 10,
                                    "priority": "HIGH"}
                                 ]},
                                {"vehicleId": "VEH002", "totalLoad": 0, "totalDistance": "0.00 km",
                                 "assignedOrders": []}
                              ],
                              "unassignedOrders": [
                                {"orderId": "ORD002", "latitude": 13.0827, "longitude": 80.2707,
                                 "address": "Anna Salai, Chennai, Tamil Nadu, India", "packageWeight": 500,
                                 "priority": "LOW", "reason": "Package weight exceeds capacity of every vehicle"}
                              ],
                              "strategy": "greedy"
                            }
                            """, JsonCompareMode.STRICT));
        }

        @Test
        void passesTheRequestedStrategyThrough() throws Exception {
            when(dispatchService.generatePlan("ortools"))
                    .thenReturn(new DispatchPlanResponse(List.of(), List.of(), "ortools"));

            mockMvc.perform(get(PLAN).param("strategy", "ortools"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"strategy\": \"ortools\"}"));
        }

        @Test
        void emptyOrdersIsAnEmptyPlanNotAnError() throws Exception {
            when(dispatchService.generatePlan(any())).thenReturn(new DispatchPlanResponse(
                    List.of(new VehiclePlanResponse("VEH001", 0, "0.00 km", List.of())), List.of(), "greedy"));

            mockMvc.perform(get(PLAN))
                    .andExpect(status().isOk())
                    .andExpect(content().json("""
                            {"dispatchPlan": [{"vehicleId": "VEH001", "totalLoad": 0, "totalDistance": "0.00 km",
                              "assignedOrders": []}], "unassignedOrders": [], "strategy": "greedy"}
                            """, JsonCompareMode.STRICT));
        }

        @Test
        void unknownStrategyIsABadRequest() throws Exception {
            when(dispatchService.generatePlan("fastest"))
                    .thenThrow(new UnknownStrategyException("fastest", List.of("greedy", "ortools")));

            assertThat(assertError(perform(get(PLAN).param("strategy", "fastest")), 400,
                    "Unknown strategy 'fastest'. Allowed values: greedy, ortools"))
                    .containsExactly("strategy: must be one of greedy, ortools");
        }

        @Test
        void noVehiclesIsABadRequestWithTheExactMessage() throws Exception {
            when(dispatchService.generatePlan(any())).thenThrow(new NoVehiclesAvailableException());

            assertError(perform(get(PLAN)), 400, "No vehicles available for dispatch");
        }

        @Test
        void unexpectedFailureIsAGeneric500WithoutDetails() throws Exception {
            when(dispatchService.generatePlan(any())).thenThrow(new IllegalStateException("secret internal detail"));

            MvcResult result = perform(get(PLAN));

            assertThat(assertError(result, 500, "An unexpected error occurred")).isEmpty();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("secret", "IllegalState");
        }
    }

    // ----------------------------------------------------------------- DELETE and others

    @Test
    void deletesAllOrders() throws Exception {
        mockMvc.perform(delete(ORDERS))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\": \"All delivery orders deleted.\", \"status\": \"success\"}",
                        JsonCompareMode.STRICT));

        verify(orderService).deleteAllOrders();
    }

    @Test
    void deletesAllVehicles() throws Exception {
        mockMvc.perform(delete(VEHICLES))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\": \"All vehicles deleted.\", \"status\": \"success\"}",
                        JsonCompareMode.STRICT));

        verify(vehicleService).deleteAllVehicles();
    }

    @Test
    void unsupportedMethodIs405WithAllowHeader() throws Exception {
        MvcResult result = mockMvc.perform(put(ORDERS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(header().exists(HttpHeaders.ALLOW))
                .andReturn();

        assertThat(assertError(result, 405, "Request method 'PUT' is not supported for this endpoint"))
                .containsExactly("supported methods: DELETE, POST");
    }

    @Test
    void unacceptableAcceptHeaderIs406() throws Exception {
        mockMvc.perform(get(PLAN).accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable());
        verifyNoInteractions(dispatchService);
    }

    @Test
    void unknownPathIs404InTheSameShape() throws Exception {
        assertThat(assertError(perform(get("/api/dispatch/nothing-here")), 404, "Resource not found"))
                .containsExactly("path: no endpoint GET /api/dispatch/nothing-here");
    }

    @Test
    void wrongTypeQueryParameterOnPlanIsStillHandled() throws Exception {
        // strategy is a free String, so even odd values reach the service, which decides.
        when(dispatchService.generatePlan("123")).thenThrow(new UnknownStrategyException("123", List.of("greedy")));

        assertError(perform(get(PLAN).param("strategy", "123")), 400, "Unknown strategy '123'. Allowed values: greedy");
    }
}
