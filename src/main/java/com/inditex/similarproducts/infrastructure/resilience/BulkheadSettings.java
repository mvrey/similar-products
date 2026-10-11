package com.inditex.similarproducts.infrastructure.resilience;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Duration;

/**
 * Bulkhead settings.
 *
 * @param maxConcurrentCalls how many calls to the external product APIs may run at the same time
 * @param maxWaitDuration    how long a call waits for a free slot before it is rejected
 */
public record BulkheadSettings(@Positive int maxConcurrentCalls, @NotNull Duration maxWaitDuration) {

    BulkheadConfig toConfig() {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(maxWaitDuration)
                .build();
    }
}
