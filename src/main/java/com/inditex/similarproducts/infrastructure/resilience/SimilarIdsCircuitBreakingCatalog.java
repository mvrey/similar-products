package com.inditex.similarproducts.infrastructure.resilience;

import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.Optional;

/**
 * Decorator with one circuit breaker for the similar ids service: if it keeps failing, requests fail fast with
 * a 502 instead of each one waiting for it. Product details pass through untouched.
 * <p>
 * It belongs below the cache, so cached similar ids are still served while the breaker is open.
 */
public class SimilarIdsCircuitBreakingCatalog implements ProductCatalog {

    private final ProductCatalog delegate;
    private final CircuitBreakerRegistry registry;
    private final CircuitBreaker breaker;

    public SimilarIdsCircuitBreakingCatalog(ProductCatalog delegate, CircuitBreakerSettings settings) {
        this.delegate = delegate;
        this.registry = CircuitBreakerRegistry.of(settings.toConfig());
        this.breaker = CircuitBreakers.withStateLogging(registry.circuitBreaker("similar-ids"));
    }

    @Override
    public Optional<List<ProductId>> findSimilarIds(ProductId productId) {
        return CircuitBreakers.call(breaker, productId, () -> delegate.findSimilarIds(productId));
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        return delegate.findById(productId);
    }

    public void registerMetrics(MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
    }
}
