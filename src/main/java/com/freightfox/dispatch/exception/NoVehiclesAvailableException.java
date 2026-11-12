package com.freightfox.dispatch.exception;

import java.util.List;

/** A dispatch plan was requested but no vehicles are stored (HTTP 400). */
public class NoVehiclesAvailableException extends BadRequestException {

    public NoVehiclesAvailableException() {
        super(ErrorMessages.NO_VEHICLES, List.of(ErrorMessages.NO_VEHICLES_HINT));
    }
}
