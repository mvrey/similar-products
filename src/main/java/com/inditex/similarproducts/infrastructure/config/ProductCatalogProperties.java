package com.inditex.similarproducts.infrastructure.config;

import com.inditex.similarproducts.infrastructure.cache.CacheSettings;
import com.inditex.similarproducts.infrastructure.resilience.BulkheadSettings;
import com.inditex.similarproducts.infrastructure.resilience.CircuitBreakerSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for product lookups, bound from the "product-catalog.*" properties.
 *
 * @param lookupTimeout  how long a request waits for a product before skipping it
 * @param cache          cache settings
 * @param circuitBreaker circuit breaker settings
 * @param bulkhead       limit on concurrent calls to the external product APIs
 */
@ConfigurationProperties(prefix = "product-catalog")
public record ProductCatalogProperties(Duration lookupTimeout, CacheSettings cache,
                                       CircuitBreakerSettings circuitBreaker, BulkheadSettings bulkhead) {
}
