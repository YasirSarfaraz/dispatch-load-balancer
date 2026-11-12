package com.freightfox.dispatch.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Generic success body: {"message": "...", "status": "success"}. */
@Schema(description = "Success acknowledgement")
public record StatusResponse(
        @Schema(example = "Delivery orders accepted.") String message,
        @Schema(example = "success") String status) {

    public static final String SUCCESS = "success";

    public static StatusResponse success(String message) {
        return new StatusResponse(message, SUCCESS);
    }
}
