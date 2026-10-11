package com.inditex.similarproducts.infrastructure.cache;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.application.port.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Decorator that caches the results of another ProductCatalog.
 * <ul>
 *   <li>Found products are cached for {@code ttl}; "not found" answers for the shorter {@code notFoundTtl}, so
 *       repeated requests for unknown products don't all reach the upstream. Failures are never cached.</li>
 *   <li>An entry read after {@code refreshAfter} is reloaded in the background while the cached value is still
 *       served, so popular products never make a request wait for an expired entry.</li>
 *   <li>Concurrent requests for the same product share a single in-flight upstream call. The call is not
 *       cancelled when a caller stops waiting, so it still fills the cache for later requests.</li>
 * </ul>
 */
public class CachingProductCatalog implements ProductCatalog, MeterBinder {

    private final AsyncLoadingCache<ProductId, Optional<List<ProductId>>> similarIdsCache;
    private final AsyncLoadingCache<ProductId, Optional<Product>> productCache;

    public CachingProductCatalog(ProductCatalog delegate, CacheSettings settings, Executor executor) {
        this(delegate, settings, executor, Ticker.systemTicker());
    }

    /**
     * Lets tests control the clock used for expiry and refresh.
     */
    CachingProductCatalog(ProductCatalog delegate, CacheSettings settings, Executor executor, Ticker ticker) {
        this.similarIdsCache = newCache(settings, executor, ticker, delegate::findSimilarIds);
        this.productCache = newCache(settings, executor, ticker, delegate::findById);
    }

    @Override
    public Optional<List<ProductId>> findSimilarIds(ProductId productId) {
        return lookUp(similarIdsCache, productId);
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        return lookUp(productCache, productId);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        CaffeineCacheMetrics.monitor(registry, similarIdsCache, "similar-ids");
        CaffeineCacheMetrics.monitor(registry, productCache, "products");
    }

    private static <V> Optional<V> lookUp(AsyncLoadingCache<ProductId, Optional<V>> cache, ProductId productId) {
        CompletableFuture<Optional<V>> lookup = cache.get(productId);
        try {
            return lookup.join();
        } catch (CompletionException e) {
            // Remove the failed lookup right away (Caffeine would do it asynchronously), so the next request
            // retries. Only removes this exact lookup, never a newer one started by another request.
            cache.asMap().remove(productId, lookup);
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

    private static <V> AsyncLoadingCache<ProductId, Optional<V>> newCache(CacheSettings settings, Executor executor,
                                                                         Ticker ticker,
                                                                         CacheLoader<ProductId, Optional<V>> loader) {
        // "writing": the expiry restarts whenever a refresh replaces the value
        Expiry<ProductId, Optional<V>> expiry = Expiry.writing((productId, value) ->
                value.isPresent() ? settings.ttl() : settings.notFoundTtl());
        return Caffeine.newBuilder()
                .expireAfter(expiry)
                .refreshAfterWrite(settings.refreshAfter())
                .maximumSize(settings.maximumSize())
                .ticker(ticker)
                .executor(executor)
                .recordStats()
                .buildAsync(loader);
    }
}
