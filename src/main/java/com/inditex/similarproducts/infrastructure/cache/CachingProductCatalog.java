package com.inditex.similarproducts.infrastructure.cache;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import com.inditex.similarproducts.domain.port.ProductCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decorator that caches the results of another ProductCatalog.
 * <ul>
 *   <li>Only successful lookups are cached: failures are retried on the next request.</li>
 *   <li>Concurrent requests for the same product share a single in-flight upstream call.</li>
 *   <li>Callers wait at most {@code lookupTimeout}; a slower upstream call keeps running in the
 *       background and fills the cache for later requests.</li>
 * </ul>
 */
public class CachingProductCatalog implements ProductCatalog {

    private static final Logger log = LoggerFactory.getLogger(CachingProductCatalog.class);

    private final ProductCatalog delegate;
    private final Duration lookupTimeout;
    private final AsyncCache<ProductId, List<ProductId>> similarIdsCache;
    private final AsyncCache<ProductId, Product> productCache;

    public CachingProductCatalog(ProductCatalog delegate, Duration lookupTimeout, Duration ttl,
                                 long maximumSize, Executor executor) {
        this.delegate = delegate;
        this.lookupTimeout = lookupTimeout;
        this.similarIdsCache = newCache(ttl, maximumSize, executor);
        this.productCache = newCache(ttl, maximumSize, executor);
    }

    @Override
    public List<ProductId> findSimilarIds(ProductId productId) {
        CompletableFuture<List<ProductId>> lookup = similarIdsCache.get(productId,
                (id, executor) -> CompletableFuture.supplyAsync(() -> delegate.findSimilarIds(id), executor));
        try {
            return await(lookup);
        } catch (TimeoutException e) {
            throw new ProductCatalogUnavailableException(productId, e);
        } catch (ExecutionException e) {
            evict(similarIdsCache, productId, lookup);
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new ProductCatalogUnavailableException(productId, e.getCause());
        }
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        // A null result (product missing or failed) is not stored, so it is retried next time
        CompletableFuture<Product> lookup = productCache.get(productId,
                (id, executor) -> CompletableFuture.supplyAsync(() -> delegate.findById(id).orElse(null), executor));
        try {
            Product product = await(lookup);
            if (product == null) {
                evict(productCache, productId, lookup);
            }
            return Optional.ofNullable(product);
        } catch (TimeoutException e) {
            log.debug("Product {} not available within {}, skipping", productId, lookupTimeout);
            return Optional.empty();
        } catch (ExecutionException e) {
            evict(productCache, productId, lookup);
            log.warn("Could not retrieve product {}: {}", productId, e.getCause().getMessage());
            return Optional.empty();
        }
    }

    /**
     * Removes a failed or empty lookup right away (Caffeine would do it asynchronously), so the next
     * request retries. Only removes this exact lookup, never a newer one started by another request.
     */
    private static <V> void evict(AsyncCache<ProductId, V> cache, ProductId productId, CompletableFuture<V> lookup) {
        cache.asMap().remove(productId, lookup);
    }

    /**
     * Waits for a shared lookup without cancelling it, so other callers and the cache still get its result.
     */
    private <T> T await(CompletableFuture<T> lookup) throws TimeoutException, ExecutionException {
        try {
            return lookup.get(lookupTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for product lookup", e);
        }
    }

    private static <V> AsyncCache<ProductId, V> newCache(Duration ttl, long maximumSize, Executor executor) {
        return Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maximumSize)
                .executor(executor)
                .buildAsync();
    }
}
