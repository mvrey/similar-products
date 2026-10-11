package com.inditex.similarproducts.infrastructure.config;

import com.inditex.similarproducts.infrastructure.cache.CacheSettings;
import com.inditex.similarproducts.infrastructure.resilience.BulkheadSettings;
import com.inditex.similarproducts.infrastructure.resilience.CircuitBreakerSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Settings for product lookups, bound from the "product-catalog.*" properties.
 * Validated at startup, so a missing or invalid setting stops the application with a message naming it.
 *
 * @param lookupTimeout  how long a request waits for a product before skipping it
 * @param cache          cache settings
 * @param circuitBreaker circuit breaker settings
 * @param bulkhead       limit on concurrent calls to the external product APIs
 */
@Validated
@ConfigurationProperties(prefix = "product-catalog")
public record ProductCatalogProperties(@NotNull Duration lookupTimeout,
                                       @NotNull @Valid CacheSettings cache,
                                       @NotNull @Valid CircuitBreakerSettings circuitBreaker,
                                       @NotNull @Valid BulkheadSettings bulkhead) {
}
