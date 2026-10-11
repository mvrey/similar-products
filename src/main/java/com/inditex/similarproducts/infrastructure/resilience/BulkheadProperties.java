package com.inditex.similarproducts.infrastructure.resilience;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bulkhead settings, bound from the "product-catalog.bulkhead.*" properties.
 *
 * @param maxConcurrentCalls how many calls to the external product APIs may run at the same time
 * @param maxWaitDuration    how long a call waits for a free slot before it is rejected
 */
@ConfigurationProperties(prefix = "product-catalog.bulkhead")
public record BulkheadProperties(int maxConcurrentCalls, Duration maxWaitDuration) {

    public BulkheadConfig toConfig() {
        return BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxWaitDuration(maxWaitDuration)
                .build();
    }
}
