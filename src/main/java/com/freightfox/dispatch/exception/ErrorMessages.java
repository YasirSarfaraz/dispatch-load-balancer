package com.freightfox.dispatch.exception;

/**
 * Every error message and message template returned by the API, in one place. Templates use
 * {@link String#format(String, Object...)} placeholders.
 */
public final class ErrorMessages {

    public static final String VALIDATION_FAILED = "Validation failed";
    public static final String MALFORMED_JSON = "Malformed JSON request";
    public static final String BODY_MISSING = "Request body is missing or malformed";
    public static final String UNEXPECTED_ERROR = "An unexpected error occurred";
    public static final String RESOURCE_NOT_FOUND = "Resource not found";
    public static final String DATA_CONFLICT =
            "The request conflicts with data that was changed concurrently; please retry";
    public static final String NO_VEHICLES = "No vehicles available for dispatch";
    public static final String NO_VEHICLES_HINT =
            "vehicles: no vehicles are stored; add some with POST /api/dispatch/vehicles";

    /** {@code Invalid value 'URGENT' for field 'orders[0].priority'. Allowed values: HIGH, MEDIUM, LOW}. */
    public static final String INVALID_ENUM_VALUE = "Invalid value '%s' for field '%s'. Allowed values: %s";
    public static final String INVALID_ENUM_VALUE_DETAIL = "%s: must be one of %s";
    /** {@code Invalid value 'abc' for field 'orders[0].latitude'. Expected type: number}. */
    public static final String INVALID_FIELD_VALUE = "Invalid value '%s' for field '%s'. Expected type: %s";
    public static final String INVALID_FIELD_TYPE = "Invalid value for field '%s'. Expected type: %s";
    public static final String EXPECTED_TYPE_DETAIL = "%s: must be of type %s";
    public static final String MALFORMED_JSON_DETAIL = "body: invalid JSON at line %d, column %d";
    public static final String BODY_MISSING_DETAIL = "body: a JSON request body is required";

    public static final String INVALID_PARAMETER = "Invalid value '%s' for parameter '%s'";
    public static final String MISSING_PARAMETER = "Missing required parameter '%s'";
    public static final String MISSING_PARAMETER_DETAIL = "%s: is required";
    public static final String METHOD_NOT_ALLOWED = "Request method '%s' is not supported for this endpoint";
    public static final String METHOD_NOT_ALLOWED_DETAIL = "supported methods: %s";
    public static final String MEDIA_TYPE_NOT_SUPPORTED = "Content type '%s' is not supported";
    public static final String MEDIA_TYPE_NOT_SUPPORTED_DETAIL = "supported content types: %s";
    public static final String RESOURCE_NOT_FOUND_DETAIL = "path: no endpoint %s %s";

    public static final String DUPLICATE_IDS = "Duplicate %s values in request";
    /** {@code orders: duplicate orderId 'ORD001'}. */
    public static final String DUPLICATE_ID_DETAIL = "%s: duplicate %s '%s'";
    public static final String UNKNOWN_STRATEGY = "Unknown strategy '%s'. Allowed values: %s";
    public static final String UNKNOWN_STRATEGY_DETAIL = "strategy: must be one of %s";
    public static final String NONE = "none";

    /** Shown as the last entry when a response would list more errors than {@link #MAX_ERRORS}. */
    public static final String MORE_ERRORS = "... and %d more errors";
    /** Upper bound on the number of entries in an error response's errors array. */
    public static final int MAX_ERRORS = 100;

    /** Longest client-supplied value echoed back in an error message. */
    public static final int MAX_ECHOED_VALUE_LENGTH = 64;
    private static final String ELLIPSIS = "...";

    private ErrorMessages() {
    }

    /**
     * Makes a client-supplied value safe to echo in a message: {@code null} becomes "null" and
     * long values are cut to {@link #MAX_ECHOED_VALUE_LENGTH} characters.
     */
    public static String echo(Object value) {
        String text = String.valueOf(value);
        if (text.length() <= MAX_ECHOED_VALUE_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_ECHOED_VALUE_LENGTH) + ELLIPSIS;
    }
}
