package com.inditex.similarproducts.infrastructure.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Circuit breaker settings, bound from the "product-catalog.circuit-breaker.*" properties.
 *
 * @param failureRateThreshold    percentage of failed calls that opens the breaker
 * @param slidingWindowSize       number of recent calls the failure rate is computed over
 * @param minimumNumberOfCalls    calls needed before the failure rate is evaluated
 * @param waitDurationInOpenState how long the breaker stays open before letting test calls through
 */
@ConfigurationProperties(prefix = "product-catalog.circuit-breaker")
public record CircuitBreakerProperties(float failureRateThreshold, int slidingWindowSize,
                                       int minimumNumberOfCalls, Duration waitDurationInOpenState) {

    public CircuitBreakerConfig toConfig() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(waitDurationInOpenState)
                .build();
    }
}
