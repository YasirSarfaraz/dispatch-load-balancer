package com.freightfox.dispatch.exception;

import java.util.Collection;
import java.util.List;

/** The requested dispatch strategy is not registered (HTTP 400). */
public class UnknownStrategyException extends BadRequestException {

    /**
     * @param requested     the strategy name the client asked for
     * @param allowedValues the registered strategy names, already sorted
     */
    public UnknownStrategyException(String requested, Collection<String> allowedValues) {
        super(String.format(ErrorMessages.UNKNOWN_STRATEGY, ErrorMessages.echo(requested), join(allowedValues)),
                List.of(String.format(ErrorMessages.UNKNOWN_STRATEGY_DETAIL, join(allowedValues))));
    }

    private static String join(Collection<String> values) {
        return values.isEmpty() ? ErrorMessages.NONE : String.join(", ", values);
    }
}
