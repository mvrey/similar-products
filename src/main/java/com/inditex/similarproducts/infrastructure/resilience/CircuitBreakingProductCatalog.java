package com.inditex.similarproducts.infrastructure.resilience;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.application.port.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Decorator that protects another ProductCatalog with circuit breakers.
 * <ul>
 *   <li>One breaker for the similar ids service: if it keeps failing, requests fail fast with a 502.</li>
 *   <li>One breaker per product for details: a product that keeps failing is skipped immediately,
 *       without affecting the others.</li>
 * </ul>
 * Only {@link ProductCatalogUnavailableException} counts as a failure: "not found" is a valid answer.
 */
public class CircuitBreakingProductCatalog implements ProductCatalog, MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingProductCatalog.class);

    private final ProductCatalog delegate;
    private final CircuitBreakerConfig config;
    // Holds only the similar ids breaker: per-product breakers are kept out of the per-breaker metrics
    private final CircuitBreakerRegistry registry;
    private final CircuitBreaker similarIdsBreaker;
    private final Cache<ProductId, CircuitBreaker> productBreakers;

    public CircuitBreakingProductCatalog(ProductCatalog delegate, CircuitBreakerSettings settings) {
        this.delegate = delegate;
        this.config = withFailuresLimitedTo(settings.toConfig());
        this.registry = CircuitBreakerRegistry.of(config);
        this.similarIdsBreaker = withStateLogging(registry.circuitBreaker("similar-ids"));
        // Per-product breakers are created on demand; forget the ones not used for a while to bound memory
        this.productBreakers = Caffeine.newBuilder()
                .expireAfterAccess(settings.idleProductBreakerExpiry())
                .maximumSize(settings.maxProductBreakers())
                .build();
    }

    @Override
    public Optional<List<ProductId>> findSimilarIds(ProductId productId) {
        return call(similarIdsBreaker, productId, () -> delegate.findSimilarIds(productId));
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        CircuitBreaker breaker = productBreakers.get(productId,
                id -> withStateLogging(CircuitBreaker.of("product-" + id, config)));
        return call(breaker, productId, () -> delegate.findById(productId));
    }

    /**
     * Publishes the similar ids breaker's metrics, and the number of open per-product breakers as a single gauge
     * (per-product metrics would create one time series per product).
     */
    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        Gauge.builder("product.catalog.product.breakers.open", productBreakers, CircuitBreakingProductCatalog::countOpen)
                .description("Per-product circuit breakers that are currently open")
                .register(meterRegistry);
    }

    private static long countOpen(Cache<ProductId, CircuitBreaker> breakers) {
        return breakers.asMap().values().stream()
                .filter(breaker -> breaker.getState() == CircuitBreaker.State.OPEN)
                .count();
    }

    private <T> T call(CircuitBreaker breaker, ProductId productId, Supplier<T> lookup) {
        try {
            return breaker.executeSupplier(lookup);
        } catch (CallNotPermittedException e) {
            throw new ProductCatalogUnavailableException(productId, e);
        }
    }

    private static CircuitBreaker withStateLogging(CircuitBreaker breaker) {
        breaker.getEventPublisher().onStateTransition(event ->
                log.info("Circuit breaker '{}': {}", breaker.getName(), event.getStateTransition()));
        return breaker;
    }

    private static CircuitBreakerConfig withFailuresLimitedTo(CircuitBreakerConfig config) {
        // Replaces the default "record every exception" predicate (recordExceptions(...) would be OR-ed with it)
        return CircuitBreakerConfig.from(config)
                .recordException(ProductCatalogUnavailableException.class::isInstance)
                .build();
    }
}
