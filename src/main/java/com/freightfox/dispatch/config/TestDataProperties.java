package com.freightfox.dispatch.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Demo test-data generator settings (prefix {@code app.test-data}).
 *
 * @param enabled     whether POST /api/dispatch/test-data is exposed
 * @param maxOrders   largest allowed orders parameter
 * @param maxVehicles largest allowed vehicles parameter
 */
@Validated
@ConfigurationProperties(prefix = "app.test-data")
public record TestDataProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("50000") @Positive int maxOrders,
        @DefaultValue("2000") @Positive int maxVehicles) {
}
