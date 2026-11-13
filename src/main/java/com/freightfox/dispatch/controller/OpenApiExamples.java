package com.freightfox.dispatch.controller;

/**
 * Example JSON bodies shown (and preloaded) in Swagger UI. Kept out of the controller so it stays
 * readable. The "assignment" examples are the ones from the assignment brief; the "sample dataset"
 * examples are the Delhi NCR dataset in samples/ (30 orders, 5 vehicles; total weight 645 exceeds
 * total capacity 500, so some LOW orders end up unassigned).
 */
public final class OpenApiExamples {

    public static final String ASSIGNMENT = "Assignment example";
    public static final String SAMPLE_DATASET = "Sample dataset (Delhi NCR)";

    public static final String ORDERS_REQUEST = """
            {
              "orders": [
                {"orderId": "ORD001", "latitude": 12.9716, "longitude": 77.5946,
                 "address": "MG Road, Bangalore, Karnataka, India", "packageWeight": 10, "priority": "HIGH"},
                {"orderId": "ORD002", "latitude": 13.0827, "longitude": 80.2707,
                 "address": "Anna Salai, Chennai, Tamil Nadu, India", "packageWeight": 20, "priority": "MEDIUM"}
              ]
            }
            """;

    public static final String ORDERS_SAMPLE_DATASET = """
            {
              "orders": [
                {"orderId": "ORD001", "latitude": 28.6139, "longitude": 77.209, "address": "Connaught Place, Delhi, India", "packageWeight": 15, "priority": "HIGH"},
                {"orderId": "ORD002", "latitude": 28.6139, "longitude": 77.209, "address": "Connaught Place, Delhi, India", "packageWeight": 10, "priority": "MEDIUM"},
                {"orderId": "ORD003", "latitude": 28.7041, "longitude": 77.1025, "address": "Karol Bagh, Delhi, India", "packageWeight": 20, "priority": "LOW"},
                {"orderId": "ORD004", "latitude": 28.5355, "longitude": 77.391, "address": "Sector 18, Noida, Uttar Pradesh, India", "packageWeight": 25, "priority": "HIGH"},
                {"orderId": "ORD005", "latitude": 28.4595, "longitude": 77.0266, "address": "Cyber Hub, Gurgaon, Haryana, India", "packageWeight": 30, "priority": "MEDIUM"},
                {"orderId": "ORD006", "latitude": 28.6139, "longitude": 77.209, "address": "Connaught Place, Delhi, India", "packageWeight": 40, "priority": "LOW"},
                {"orderId": "ORD007", "latitude": 28.7041, "longitude": 77.1025, "address": "Karol Bagh, Delhi, India", "packageWeight": 20, "priority": "HIGH"},
                {"orderId": "ORD008", "latitude": 28.5355, "longitude": 77.391, "address": "Sector 18, Noida, Uttar Pradesh, India", "packageWeight": 25, "priority": "HIGH"},
                {"orderId": "ORD009", "latitude": 28.4595, "longitude": 77.0266, "address": "Cyber Hub, Gurgaon, Haryana, India", "packageWeight": 15, "priority": "MEDIUM"},
                {"orderId": "ORD010", "latitude": 28.6139, "longitude": 77.209, "address": "Connaught Place, Delhi, India", "packageWeight": 30, "priority": "LOW"},
                {"orderId": "ORD011", "latitude": 28.7041, "longitude": 77.1025, "address": "Karol Bagh, Delhi, India", "packageWeight": 20, "priority": "HIGH"},
                {"orderId": "ORD012", "latitude": 28.5355, "longitude": 77.391, "address": "Sector 18, Noida, Uttar Pradesh, India", "packageWeight": 10, "priority": "MEDIUM"},
                {"orderId": "ORD013", "latitude": 28.4595, "longitude": 77.0266, "address": "Cyber Hub, Gurgaon, Haryana, India", "packageWeight": 25, "priority": "HIGH"},
                {"orderId": "ORD014", "latitude": 28.6139, "longitude": 77.209, "address": "Connaught Place, Delhi, India", "packageWeight": 15, "priority": "LOW"},
                {"orderId": "ORD015", "latitude": 28.7041, "longitude": 77.1025, "address": "Karol Bagh, Delhi, India", "packageWeight": 20, "priority": "HIGH"},
                {"orderId": "ORD016", "latitude": 28.5355, "longitude": 77.391, "address": "Sector 18, Noida, Uttar Pradesh, India", "packageWeight": 30, "priority": "LOW"},
                {"orderId": "ORD017", "latitude": 28.4595, "longitude": 77.0266, "address": "Cyber Hub, Gurgaon, Haryana, India", "packageWeight": 15, "priority": "MEDIUM"},
                {"orderId": "ORD018", "latitude": 28.6139, "longitude": 77.209, "address": "Connaught Place, Delhi, India", "packageWeight": 10, "priority": "HIGH"},
                {"orderId": "ORD019", "latitude": 28.7041, "longitude": 77.1025, "address": "Karol Bagh, Delhi, India", "packageWeight": 20, "priority": "LOW"},
                {"orderId": "ORD020", "latitude": 28.5355, "longitude": 77.391, "address": "Sector 18, Noida, Uttar Pradesh, India", "packageWeight": 30, "priority": "HIGH"},
                {"orderId": "ORD021", "latitude": 28.6139, "longitude": 77.209, "address": "Connaught Place, Delhi, India", "packageWeight": 25, "priority": "MEDIUM"},
                {"orderId": "ORD022", "latitude": 28.7041, "longitude": 77.1025, "address": "Karol Bagh, Delhi, India", "packageWeight": 20, "priority": "LOW"},
                {"orderId": "ORD023", "latitude": 28.5355, "longitude": 77.391, "address": "Sector 18, Noida, Uttar Pradesh, India", "packageWeight": 30, "priority": "HIGH"},
                {"orderId": "ORD024", "latitude": 28.4595, "longitude": 77.0266, "address": "Cyber Hub, Gurgaon, Haryana, India", "packageWeight": 15, "priority": "LOW"},
                {"orderId": "ORD025", "latitude": 28.6139, "longitude": 77.209, "address": "Connaught Place, Delhi, India", "packageWeight": 20, "priority": "MEDIUM"},
                {"orderId": "ORD026", "latitude": 28.7041, "longitude": 77.1025, "address": "Karol Bagh, Delhi, India", "packageWeight": 25, "priority": "HIGH"},
                {"orderId": "ORD027", "latitude": 28.5355, "longitude": 77.391, "address": "Sector 18, Noida, Uttar Pradesh, India", "packageWeight": 20, "priority": "MEDIUM"},
                {"orderId": "ORD028", "latitude": 28.4595, "longitude": 77.0266, "address": "Cyber Hub, Gurgaon, Haryana, India", "packageWeight": 15, "priority": "LOW"},
                {"orderId": "ORD029", "latitude": 28.6139, "longitude": 77.209, "address": "Connaught Place, Delhi, India", "packageWeight": 30, "priority": "HIGH"},
                {"orderId": "ORD030", "latitude": 28.7041, "longitude": 77.1025, "address": "Karol Bagh, Delhi, India", "packageWeight": 20, "priority": "LOW"}
              ]
            }
            """;

    public static final String VEHICLES_REQUEST = """
            {
              "vehicles": [
                {"vehicleId": "VEH001", "capacity": 100, "currentLatitude": 12.9716, "currentLongitude": 77.6413,
                 "currentAddress": "Indiranagar, Bangalore, Karnataka, India"},
                {"vehicleId": "VEH002", "capacity": 150, "currentLatitude": 13.0674, "currentLongitude": 80.2376,
                 "currentAddress": "T Nagar, Chennai, Tamil Nadu, India"}
              ]
            }
            """;

    public static final String VEHICLES_SAMPLE_DATASET = """
            {
              "vehicles": [
                {"vehicleId": "VEH001", "capacity": 100, "currentLatitude": 28.7041, "currentLongitude": 77.1025, "currentAddress": "Karol Bagh, Delhi, India"},
                {"vehicleId": "VEH002", "capacity": 80, "currentLatitude": 28.5355, "currentLongitude": 77.391, "currentAddress": "Sector 18, Noida, Uttar Pradesh, India"},
                {"vehicleId": "VEH003", "capacity": 120, "currentLatitude": 28.4595, "currentLongitude": 77.0266, "currentAddress": "Cyber Hub, Gurgaon, Haryana, India"},
                {"vehicleId": "VEH004", "capacity": 90, "currentLatitude": 28.6139, "currentLongitude": 77.209, "currentAddress": "Connaught Place, Delhi, India"},
                {"vehicleId": "VEH005", "capacity": 110, "currentLatitude": 28.7041, "currentLongitude": 77.1025, "currentAddress": "Karol Bagh, Delhi, India"}
              ]
            }
            """;

    public static final String ORDERS_ACCEPTED_RESPONSE = """
            {"message": "Delivery orders accepted.", "status": "success"}
            """;
    public static final String VEHICLES_ACCEPTED_RESPONSE = """
            {"message": "Vehicle details accepted.", "status": "success"}
            """;
    public static final String ORDERS_DELETED_RESPONSE = """
            {"message": "All delivery orders deleted.", "status": "success"}
            """;
    public static final String VEHICLES_DELETED_RESPONSE = """
            {"message": "All vehicles deleted.", "status": "success"}
            """;

    /** Plan for the assignment example (distances are Haversine, open routes). */
    public static final String PLAN_RESPONSE = """
            {
              "dispatchPlan": [
                {
                  "vehicleId": "VEH001",
                  "totalLoad": 10,
                  "totalDistance": "5.06 km",
                  "assignedOrders": [
                    {"orderId": "ORD001", "latitude": 12.9716, "longitude": 77.5946,
                     "address": "MG Road, Bangalore, Karnataka, India", "packageWeight": 10, "priority": "HIGH"}
                  ]
                },
                {
                  "vehicleId": "VEH002",
                  "totalLoad": 20,
                  "totalDistance": "3.97 km",
                  "assignedOrders": [
                    {"orderId": "ORD002", "latitude": 13.0827, "longitude": 80.2707,
                     "address": "Anna Salai, Chennai, Tamil Nadu, India", "packageWeight": 20, "priority": "MEDIUM"}
                  ]
                }
              ],
              "unassignedOrders": [],
              "strategy": "greedy"
            }
            """;

    public static final String VALIDATION_ERROR = """
            {
              "status": "error",
              "message": "Validation failed",
              "errors": ["orders[0].latitude: must be less than or equal to 90.0",
                         "orders[1].packageWeight: must be greater than 0"],
              "timestamp": "2026-11-12T10:15:30.123Z"
            }
            """;
    public static final String VEHICLE_VALIDATION_ERROR = """
            {
              "status": "error",
              "message": "Validation failed",
              "errors": ["vehicles[0].capacity: must be greater than 0"],
              "timestamp": "2026-11-12T10:15:30.123Z"
            }
            """;
    public static final String INVALID_PRIORITY_ERROR = """
            {
              "status": "error",
              "message": "Invalid value 'URGENT' for field 'orders[0].priority'. Allowed values: HIGH, MEDIUM, LOW",
              "errors": ["orders[0].priority: must be one of HIGH, MEDIUM, LOW"],
              "timestamp": "2026-11-12T10:15:30.123Z"
            }
            """;
    public static final String DUPLICATE_ORDER_ERROR = """
            {
              "status": "error",
              "message": "Duplicate orderId values in request",
              "errors": ["orders: duplicate orderId 'ORD001'"],
              "timestamp": "2026-11-12T10:15:30.123Z"
            }
            """;
    public static final String DUPLICATE_VEHICLE_ERROR = """
            {
              "status": "error",
              "message": "Duplicate vehicleId values in request",
              "errors": ["vehicles: duplicate vehicleId 'VEH001'"],
              "timestamp": "2026-11-12T10:15:30.123Z"
            }
            """;
    public static final String MALFORMED_JSON_ERROR = """
            {
              "status": "error",
              "message": "Malformed JSON request",
              "errors": ["body: invalid JSON at line 3, column 5"],
              "timestamp": "2026-11-12T10:15:30.123Z"
            }
            """;
    public static final String UNKNOWN_STRATEGY_ERROR = """
            {
              "status": "error",
              "message": "Unknown strategy 'fastest'. Allowed values: greedy, ortools",
              "errors": ["strategy: must be one of greedy, ortools"],
              "timestamp": "2026-11-12T10:15:30.123Z"
            }
            """;
    public static final String NO_VEHICLES_ERROR = """
            {
              "status": "error",
              "message": "No vehicles available for dispatch",
              "errors": ["vehicles: no vehicles are stored; add some with POST /api/dispatch/vehicles"],
              "timestamp": "2026-11-12T10:15:30.123Z"
            }
            """;
    public static final String CONCURRENT_CONFLICT_ERROR = """
            {
              "status": "error",
              "message": "The request conflicts with data that was changed concurrently; please retry",
              "errors": [],
              "timestamp": "2026-11-12T10:15:30.123Z"
            }
            """;
    public static final String UNSUPPORTED_MEDIA_TYPE_ERROR = """
            {
              "status": "error",
              "message": "Content type 'text/plain' is not supported",
              "errors": ["supported content types: application/json"],
              "timestamp": "2026-11-12T10:15:30.123Z"
            }
            """;

    private OpenApiExamples() {
    }
}
