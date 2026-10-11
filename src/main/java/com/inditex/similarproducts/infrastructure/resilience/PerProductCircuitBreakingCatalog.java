package com.inditex.similarproducts.infrastructure.resilience;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.Optional;

/**
 * Decorator with one circuit breaker per product for product details: a product that keeps failing or timing
 * out is skipped immediately, without affecting the others. Similar ids pass through untouched.
 * <p>
 * It belongs above the time limit, so that a lookup that does not answer in time counts as a failure. Below it,
 * a product that never answers would record a failure only when the upstream call finally gives up, and request
 * coalescing makes that one failure per read timeout: far too few to ever open the breaker.
 */
public class PerProductCircuitBreakingCatalog implements ProductCatalog {

    private final ProductCatalog delegate;
    private final CircuitBreakerConfig config;
    private final Cache<ProductId, CircuitBreaker> breakers;

    public PerProductCircuitBreakingCatalog(ProductCatalog delegate, CircuitBreakerSettings settings) {
        this.delegate = delegate;
        this.config = settings.toConfig();
        // Breakers are created on demand; forget the ones not used for a while to bound memory
        this.breakers = Caffeine.newBuilder()
                .expireAfterAccess(settings.idleProductBreakerExpiry())
                .maximumSize(settings.maxProductBreakers())
                .build();
    }

    @Override
    public Optional<List<ProductId>> findSimilarIds(ProductId productId) {
        return delegate.findSimilarIds(productId);
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        CircuitBreaker breaker = breakers.get(productId,
                id -> CircuitBreakers.withStateLogging(CircuitBreaker.of("product-" + id, config)));
        return CircuitBreakers.call(breaker, productId, () -> delegate.findById(productId));
    }

    /**
     * Publishes the number of open breakers as a single gauge (per-product metrics would create one time series
     * per product).
     */
    public void registerMetrics(MeterRegistry meterRegistry) {
        Gauge.builder("product.catalog.product.breakers.open", breakers,
                        PerProductCircuitBreakingCatalog::countOpen)
                .description("Per-product circuit breakers that are currently open")
                .register(meterRegistry);
    }

    private static long countOpen(Cache<ProductId, CircuitBreaker> breakers) {
        return breakers.asMap().values().stream()
                .filter(breaker -> breaker.getState() == CircuitBreaker.State.OPEN)
                .count();
    }
}
