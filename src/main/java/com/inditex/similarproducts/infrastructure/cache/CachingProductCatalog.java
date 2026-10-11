package com.inditex.similarproducts.infrastructure.cache;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import com.inditex.similarproducts.domain.port.ProductCatalog;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Decorator that caches the results of another ProductCatalog.
 * <ul>
 *   <li>Only successful lookups are cached: failures and missing products are looked up again next time.</li>
 *   <li>Concurrent requests for the same product share a single in-flight upstream call. The call is not
 *       cancelled when a caller stops waiting, so it still fills the cache for later requests.</li>
 * </ul>
 */
public class CachingProductCatalog implements ProductCatalog {

    private final ProductCatalog delegate;
    private final AsyncCache<ProductId, List<ProductId>> similarIdsCache;
    private final AsyncCache<ProductId, Product> productCache;

    public CachingProductCatalog(ProductCatalog delegate, Duration ttl, long maximumSize, Executor executor) {
        this(delegate, ttl, maximumSize, executor, Ticker.systemTicker());
    }

    /**
     * Lets tests control the clock used for expiry.
     */
    CachingProductCatalog(ProductCatalog delegate, Duration ttl, long maximumSize, Executor executor,
                          Ticker ticker) {
        this.delegate = delegate;
        this.similarIdsCache = newCache(ttl, maximumSize, executor, ticker);
        this.productCache = newCache(ttl, maximumSize, executor, ticker);
    }

    @Override
    public Optional<List<ProductId>> findSimilarIds(ProductId productId) {
        return lookUp(similarIdsCache, productId, delegate::findSimilarIds);
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        return lookUp(productCache, productId, delegate::findById);
    }

    private static <V> Optional<V> lookUp(AsyncCache<ProductId, V> cache, ProductId productId,
                                          Function<ProductId, Optional<V>> loader) {
        // A missing product is loaded as null, which Caffeine does not store
        CompletableFuture<V> lookup = cache.get(productId,
                (id, executor) -> CompletableFuture.supplyAsync(() -> loader.apply(id).orElse(null), executor));
        try {
            V value = lookup.join();
            if (value == null) {
                evict(cache, productId, lookup);
            }
            return Optional.ofNullable(value);
        } catch (CompletionException e) {
            evict(cache, productId, lookup);
            throw rethrowable(productId, e.getCause());
        }
    }

    /**
     * Unwraps the exception thrown by the delegate so callers see the same exceptions as without the cache.
     */
    private static RuntimeException rethrowable(ProductId productId, Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new ProductCatalogUnavailableException(productId, cause);
    }

    /**
     * Removes a failed or empty lookup right away (Caffeine would do it asynchronously), so the next
     * request retries. Only removes this exact lookup, never a newer one started by another request.
     */
    private static <V> void evict(AsyncCache<ProductId, V> cache, ProductId productId, CompletableFuture<V> lookup) {
        cache.asMap().remove(productId, lookup);
    }

    private static <V> AsyncCache<ProductId, V> newCache(Duration ttl, long maximumSize, Executor executor,
                                                         Ticker ticker) {
        return Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .ticker(ticker)
                .maximumSize(maximumSize)
                .executor(executor)
                .buildAsync();
    }
}
