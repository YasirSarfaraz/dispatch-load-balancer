package com.freightfox.dispatch.exception;

import java.util.List;

/**
 * Base class for client errors. The global exception handler maps it (and every subclass) to
 * HTTP 400 with {@link #getMessage()} as message and {@link #getErrors()} as errors.
 */
public class BadRequestException extends RuntimeException {

    private final transient List<String> errors;

    public BadRequestException(String message) {
        this(message, List.of());
    }

    public BadRequestException(String message, List<String> errors) {
        super(message);
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
