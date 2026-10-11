package com.inditex.similarproducts.infrastructure.resilience;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import com.inditex.similarproducts.domain.port.ProductCatalog;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
public class CircuitBreakingProductCatalog implements ProductCatalog {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakingProductCatalog.class);

    // Per-product breakers are created on demand; forget the ones not used for a while to bound memory
    private static final Duration IDLE_BREAKER_EXPIRY = Duration.ofMinutes(30);
    private static final long MAX_PRODUCT_BREAKERS = 10_000;

    private final ProductCatalog delegate;
    private final CircuitBreakerConfig config;
    private final CircuitBreaker similarIdsBreaker;
    private final Cache<ProductId, CircuitBreaker> productBreakers;

    public CircuitBreakingProductCatalog(ProductCatalog delegate, CircuitBreakerConfig config) {
        this.delegate = delegate;
        this.config = withFailuresLimitedTo(config);
        this.similarIdsBreaker = newBreaker("similar-ids");
        this.productBreakers = Caffeine.newBuilder()
                .expireAfterAccess(IDLE_BREAKER_EXPIRY)
                .maximumSize(MAX_PRODUCT_BREAKERS)
                .build();
    }

    @Override
    public Optional<List<ProductId>> findSimilarIds(ProductId productId) {
        return call(similarIdsBreaker, productId, () -> delegate.findSimilarIds(productId));
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        CircuitBreaker breaker = productBreakers.get(productId, id -> newBreaker("product-" + id));
        return call(breaker, productId, () -> delegate.findById(productId));
    }

    private <T> T call(CircuitBreaker breaker, ProductId productId, Supplier<T> lookup) {
        try {
            return breaker.executeSupplier(lookup);
        } catch (CallNotPermittedException e) {
            throw new ProductCatalogUnavailableException(productId, e);
        }
    }

    private CircuitBreaker newBreaker(String name) {
        CircuitBreaker breaker = CircuitBreaker.of(name, config);
        breaker.getEventPublisher().onStateTransition(event ->
                log.info("Circuit breaker '{}': {}", name, event.getStateTransition()));
        return breaker;
    }

    private static CircuitBreakerConfig withFailuresLimitedTo(CircuitBreakerConfig config) {
        // Replaces the default "record every exception" predicate (recordExceptions(...) would be OR-ed with it)
        return CircuitBreakerConfig.from(config)
                .recordException(ProductCatalogUnavailableException.class::isInstance)
                .build();
    }
}
