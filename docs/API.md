# API reference

[Back to README](../README.md)

Base path: `/api/dispatch`. All request and response bodies are JSON (`Content-Type: application/json`).
Interactive documentation is served by the application at `/swagger-ui.html` (the root URL redirects
there; OpenAPI 3 at `/v3/api-docs`), with two preloaded request examples: "Assignment example" and
"Sample dataset (Delhi NCR)". Every API response carries a `Server-Timing: app;dur=<ms>` header with the
server's processing time.

- [Endpoints](#endpoints)
- [Field rules](#field-rules)
- [Plan response](#plan-response)
- [Error response](#error-response)
- [Status codes](#status-codes)
- [curl examples](#curl-examples)

## Endpoints

| Method and path | Parameters / body | Success (200) | Error cases |
|---|---|---|---|
| `POST /api/dispatch/orders` | Body `{"orders": [ ... ]}`. Upsert by `orderId`. | `{"message": "Delivery orders accepted.", "status": "success"}` | 400 validation error, invalid priority, wrong type (e.g. `10.5`), duplicate `orderId`, malformed JSON, missing body; 409 concurrent insert of the same new ID; 415 wrong content type |
| `POST /api/dispatch/vehicles` | Body `{"vehicles": [ ... ]}`. Upsert by `vehicleId`. | `{"message": "Vehicle details accepted.", "status": "success"}` | Same as above, for vehicles |
| `GET /api/dispatch/plan` | Query `strategy` (optional): `greedy` (default) or `ortools`; case-insensitive, trimmed; blank means `greedy`. | [Dispatch plan](#plan-response) | 400 unknown strategy; 400 no vehicles stored. No orders stored: 200 with every vehicle empty |
| `DELETE /api/dispatch/orders` | - | `{"message": "All delivery orders deleted.", "status": "success"}` | - |
| `DELETE /api/dispatch/vehicles` | - | `{"message": "All vehicles deleted.", "status": "success"}` | - |
| `POST /api/dispatch/test-data` | Query `orders` (default 5000, 1 to 50000), `vehicles` (default 100, 1 to 2000), `seed` (default 42). Demo only; **replaces** all orders and vehicles. | `{"message": "Test data generated. All existing orders and vehicles were replaced.", "status": "success", "ordersGenerated": 5000, "vehiclesGenerated": 100, "seed": 42, "elapsedMs": ...}` | 400 out of range or not a number; 404 when `app.test-data.enabled=false` |
| `GET /actuator/health` | - | `{"status": "UP"}` | - |
| `GET /actuator/info` | - | Version, deployed commit and database, e.g. `{"app": {"version": "1.0.0", "commit": "..."}, "database": {"product": "PostgreSQL", "version": "16.15"}}` | - |
| `GET /v3/api-docs` | - | OpenAPI 3 JSON | - |
| `GET /swagger-ui.html` (or `GET /`) | - | Swagger UI | - |

## Field rules

| Field | Rule |
|---|---|
| `orders` / `vehicles` | Required, not empty, no `null` elements |
| `orderId` / `vehicleId` | Not blank, at most 64 characters, unique within the request |
| `latitude` / `currentLatitude` | Required number, -90 to 90 |
| `longitude` / `currentLongitude` | Required number, -180 to 180 |
| `address` / `currentAddress` | Not blank, at most 500 characters |
| `packageWeight` / `capacity` | Required whole number, greater than 0 (`10.5` is rejected, not truncated) |
| `priority` | Required, one of `HIGH`, `MEDIUM`, `LOW` (case-insensitive; numbers such as `1` are rejected) |

Parsing is strict: numbers sent as strings (`"packageWeight": "10"`), values that overflow an int
(`3000000000`) and trailing text after the JSON body are rejected with 400. Each POST is all-or-nothing:
the whole batch is validated first, and any invalid item or duplicate ID returns 400 with nothing stored.

## Plan response

```json
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
    }
  ],
  "unassignedOrders": [],
  "strategy": "greedy"
}
```

- Every stored vehicle is listed, sorted by `vehicleId`; unused vehicles have `totalLoad` 0,
  `totalDistance` `"0.00 km"` and an empty `assignedOrders` list.
- `assignedOrders` are in actual visiting order. `totalDistance` is a string with two decimals.
- `unassignedOrders` (an addition to the required contract) lists every order that could not be placed,
  with all order fields plus `reason`, sorted HIGH first, then by `orderId`. Reasons:
  - `Package weight exceeds capacity of every vehicle`
  - `Insufficient remaining capacity across fleet` (greedy)
  - `Dropped by optimizer: insufficient capacity` (OR-Tools)
- `strategy` (an addition) names the algorithm that actually ran. It is `greedy` when `ortools` was
  requested but fell back (see [OR-Tools fallbacks](ALGORITHMS.md#cap-and-fallbacks)).

## Error response

Every error, from any endpoint, has one shape:

```json
{
  "status": "error",
  "message": "Validation failed",
  "errors": ["orders[0].latitude: must be less than or equal to 90.0"],
  "timestamp": "2026-11-12T10:15:30.123Z"
}
```

- `errors` entries are `"field: reason"`; validation errors are sorted alphabetically and use the
  standard Bean Validation (Hibernate Validator) English messages.
- At most 100 entries are returned, followed by `"... and N more errors"`. Client values echoed in
  messages are cut to 64 characters.
- No stack traces or exception class names are ever returned; unexpected faults are logged server-side
  and answered with `500` and `"An unexpected error occurred"`.

## Status codes

| Code | When |
|---|---|
| 200 | Success |
| 400 | Any invalid input: validation, wrong JSON type, invalid enum value, malformed or missing body, duplicate IDs, unknown strategy, no vehicles for a plan, test-data parameters out of range or not numeric |
| 404 | Unknown path (`"Resource not found"`), or the test-data endpoint when disabled |
| 405 | Wrong method (`"Request method 'PUT' is not supported for this endpoint"`), with an `Allow` header |
| 406 | The `Accept` header allows no JSON (no body) |
| 409 | Two concurrent requests inserting the same new ID (`"The request conflicts with data that was changed concurrently; please retry"`) |
| 415 | Non-JSON content type (`"Content type 'text/plain' is not supported"`) |
| 500 | Genuine server faults only; never for bad input |

## curl examples

The examples call the live deployment. Run them from the project root (the `samples/` paths are
relative) after setting the base URL once; for a local run use `BASE=http://localhost:8080`. On Windows
use Git Bash, or `curl.exe` in PowerShell.

```bash
BASE=https://dispatch-load-balancer-yl5j.onrender.com
``` The [Postman collection](../postman/DispatchLoadBalancer.postman_collection.json)
covers the same cases with assertions and targets the live deployment by default.

### Happy path

```bash
# Upload the 30 sample orders and 5 sample vehicles
curl -X POST $BASE/api/dispatch/orders \
  -H "Content-Type: application/json" -d @samples/orders.json
# {"message":"Delivery orders accepted.","status":"success"}

curl -X POST $BASE/api/dispatch/vehicles \
  -H "Content-Type: application/json" -d @samples/vehicles.json
# {"message":"Vehicle details accepted.","status":"success"}

# Plan with the default strategy (greedy), or choose one explicitly
curl $BASE/api/dispatch/plan
curl "$BASE/api/dispatch/plan?strategy=ortools"
```

Excerpt of the greedy plan for the sample data:

```json
{
  "dispatchPlan": [
    ...
    {
      "vehicleId": "VEH002",
      "totalLoad": 80,
      "totalDistance": "0.00 km",
      "assignedOrders": [
        {"orderId": "ORD020", "latitude": 28.5355, "longitude": 77.391, "address": "Sector 18, Noida, Uttar Pradesh, India", "packageWeight": 30, "priority": "HIGH"},
        {"orderId": "ORD023", "latitude": 28.5355, "longitude": 77.391, "address": "Sector 18, Noida, Uttar Pradesh, India", "packageWeight": 30, "priority": "HIGH"},
        {"orderId": "ORD027", "latitude": 28.5355, "longitude": 77.391, "address": "Sector 18, Noida, Uttar Pradesh, India", "packageWeight": 20, "priority": "MEDIUM"}
      ]
    },
    ...
  ],
  "unassignedOrders": [
    {"orderId": "ORD003", "latitude": 28.7041, "longitude": 77.1025, "address": "Karol Bagh, Delhi, India", "packageWeight": 20, "priority": "LOW", "reason": "Insufficient remaining capacity across fleet"},
    ...
  ],
  "strategy": "greedy"
}
```

### Clearing data

```bash
curl -X DELETE $BASE/api/dispatch/orders
# {"message":"All delivery orders deleted.","status":"success"}

curl -X DELETE $BASE/api/dispatch/vehicles
# {"message":"All vehicles deleted.","status":"success"}
```

### Test data (demo; replaces all orders and vehicles)

```bash
curl -X POST "$BASE/api/dispatch/test-data?orders=5000&vehicles=100&seed=42"
# {"message":"Test data generated. All existing orders and vehicles were replaced.","status":"success",
#  "ordersGenerated":5000,"vehiclesGenerated":100,"seed":42,"elapsedMs":<time to generate and store>}

curl $BASE/api/dispatch/plan
```

With the defaults (5,000 orders of 1-50 kg, about 127,500 kg in total, against 100 vehicles of
100-500 kg, about 30,000 kg in total) the fleet can carry only about a quarter of the demand, so most
orders, including some HIGH ones, are correctly reported as unassigned; every HIGH order that fits is
placed before any MEDIUM or LOW one. Use `vehicles=500` (about 150,000 kg) to see a fleet that serves
most of the demand.

### Health and API docs

```bash
curl $BASE/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}

curl $BASE/actuator/info
# {"app":{"name":"dispatch-load-balancer","version":"1.0.0","commit":"<git commit>"},
#  "database":{"product":"PostgreSQL","version":"16.x"}}

curl $BASE/v3/api-docs
```

### Error examples

The bodies below are built from the message constants in the code; `timestamp` values are
illustrative. Use `curl -i` to see the status line.

Invalid priority (400):

```bash
curl -i -X POST $BASE/api/dispatch/orders -H "Content-Type: application/json" \
  -d '{"orders":[{"orderId":"ORD100","latitude":28.6139,"longitude":77.209,"address":"Connaught Place, Delhi, India","packageWeight":10,"priority":"URGENT"}]}'
```
```json
{"status":"error","message":"Invalid value 'URGENT' for field 'orders[0].priority'. Allowed values: HIGH, MEDIUM, LOW","errors":["orders[0].priority: must be one of HIGH, MEDIUM, LOW"],"timestamp":"2026-11-12T10:15:30.123Z"}
```

Latitude out of range (400):

```bash
curl -i -X POST $BASE/api/dispatch/orders -H "Content-Type: application/json" \
  -d '{"orders":[{"orderId":"ORD100","latitude":91,"longitude":77.209,"address":"Connaught Place, Delhi, India","packageWeight":10,"priority":"HIGH"}]}'
```
```json
{"status":"error","message":"Validation failed","errors":["orders[0].latitude: must be less than or equal to 90.0"],"timestamp":"2026-11-12T10:15:30.123Z"}
```

Non-integer weight (400):

```bash
curl -i -X POST $BASE/api/dispatch/orders -H "Content-Type: application/json" \
  -d '{"orders":[{"orderId":"ORD100","latitude":28.6139,"longitude":77.209,"address":"Connaught Place, Delhi, India","packageWeight":10.5,"priority":"HIGH"}]}'
```
```json
{"status":"error","message":"Invalid value '10.5' for field 'orders[0].packageWeight'. Expected type: integer","errors":["orders[0].packageWeight: must be of type integer"],"timestamp":"2026-11-12T10:15:30.123Z"}
```

Duplicate IDs in one request (400; nothing is stored):

```bash
curl -i -X POST $BASE/api/dispatch/orders -H "Content-Type: application/json" \
  -d '{"orders":[{"orderId":"ORD001","latitude":28.6139,"longitude":77.209,"address":"Connaught Place, Delhi, India","packageWeight":10,"priority":"HIGH"},{"orderId":"ORD001","latitude":28.6139,"longitude":77.209,"address":"Connaught Place, Delhi, India","packageWeight":12,"priority":"LOW"}]}'
```
```json
{"status":"error","message":"Duplicate orderId values in request","errors":["orders: duplicate orderId 'ORD001'"],"timestamp":"2026-11-12T10:15:30.123Z"}
```

Malformed JSON (400; line and column point at the syntax error):

```bash
curl -i -X POST $BASE/api/dispatch/orders -H "Content-Type: application/json" -d '{"orders": ['
```
```json
{"status":"error","message":"Malformed JSON request","errors":["body: invalid JSON at line 1, column 13"],"timestamp":"2026-11-12T10:15:30.123Z"}
```

Unknown strategy (400; checked before anything else, even with an empty database):

```bash
curl -i "$BASE/api/dispatch/plan?strategy=fastest"
```
```json
{"status":"error","message":"Unknown strategy 'fastest'. Allowed values: greedy, ortools","errors":["strategy: must be one of greedy, ortools"],"timestamp":"2026-11-12T10:15:30.123Z"}
```

No vehicles stored (400):

```bash
curl -X DELETE $BASE/api/dispatch/vehicles
curl -i $BASE/api/dispatch/plan
```
```json
{"status":"error","message":"No vehicles available for dispatch","errors":["vehicles: no vehicles are stored; add some with POST /api/dispatch/vehicles"],"timestamp":"2026-11-12T10:15:30.123Z"}
```

Test-data limits (400):

```bash
curl -i -X POST "$BASE/api/dispatch/test-data?orders=60000"
```
```json
{"status":"error","message":"orders must be between 1 and 50000","errors":["orders: must be between 1 and 50000"],"timestamp":"2026-11-12T10:15:30.123Z"}
```
