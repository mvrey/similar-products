package com.inditex.similarproducts.infrastructure.resilience;

import com.inditex.similarproducts.application.port.ProductCatalogBusyException;
import com.inditex.similarproducts.application.port.ProductCatalogUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;

/**
 * Circuit breaker settings.
 *
 * @param failureRateThreshold     percentage of failed calls that opens a breaker
 * @param slidingWindowSize        number of recent calls the failure rate is computed over
 * @param minimumNumberOfCalls     calls needed before the failure rate is evaluated
 * @param waitDurationInOpenState  how long a breaker stays open before letting test calls through
 * @param idleProductBreakerExpiry how long an unused per-product breaker is kept before it is forgotten
 * @param maxProductBreakers       upper bound on the number of per-product breakers kept in memory
 */
public record CircuitBreakerSettings(float failureRateThreshold, int slidingWindowSize, int minimumNumberOfCalls,
                                     Duration waitDurationInOpenState, Duration idleProductBreakerExpiry,
                                     long maxProductBreakers) {

    /**
     * Only an unavailable catalog (error, timeout, invalid data) counts as a failure. "Not found" is a valid
     * answer, and a call refused because this service is busy says nothing about the catalog's health.
     */
    CircuitBreakerConfig toConfig() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(waitDurationInOpenState)
                // Replaces the default "record every exception" predicate
                .recordException(e -> e instanceof ProductCatalogUnavailableException
                        && !(e instanceof ProductCatalogBusyException))
                .build();
    }
}
