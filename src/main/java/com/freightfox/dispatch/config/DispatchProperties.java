package com.freightfox.dispatch.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Tuning for persistence and the dispatch algorithms (prefix {@code app.dispatch}).
 *
 * @param upsertChunkSize rows per upsert chunk (one findAllById + one saveAll per chunk)
 * @param greedy          greedy strategy tuning
 * @param ortools         OR-Tools strategy tuning
 */
@Validated
@ConfigurationProperties(prefix = "app.dispatch")
public record DispatchProperties(
        @DefaultValue("1000") @Positive int upsertChunkSize,
        @DefaultValue @Valid Greedy greedy,
        @DefaultValue @Valid OrTools ortools) {

    /**
     * @param twoOptMaxIterations max improvement passes of 2-opt per vehicle
     * @param twoOptTimeBudget    max wall time spent in 2-opt per vehicle
     */
    public record Greedy(
            @DefaultValue("1000") @Positive int twoOptMaxIterations,
            @DefaultValue("25ms") Duration twoOptTimeBudget) {
    }

    /**
     * @param timeLimit solver time limit
     * @param maxOrders above this many orders OR-Tools is skipped and greedy is used (matrix is N^2)
     */
    public record OrTools(
            @DefaultValue("3s") Duration timeLimit,
            @DefaultValue("1000") @Positive int maxOrders) {
    }
}
