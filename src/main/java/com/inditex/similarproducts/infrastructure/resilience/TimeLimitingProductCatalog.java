package com.inditex.similarproducts.infrastructure.resilience;

import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import com.inditex.similarproducts.domain.port.ProductCatalog;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Decorator that limits how long a caller waits for another ProductCatalog.
 * A lookup that takes longer is reported as unavailable. It is not cancelled, so a slower upstream
 * call still completes in the background and can fill the cache below this decorator.
 */
public class TimeLimitingProductCatalog implements ProductCatalog {

    private final ProductCatalog delegate;
    private final Duration timeout;
    private final Executor executor;

    public TimeLimitingProductCatalog(ProductCatalog delegate, Duration timeout, Executor executor) {
        this.delegate = delegate;
        this.timeout = timeout;
        this.executor = executor;
    }

    @Override
    public Optional<List<ProductId>> findSimilarIds(ProductId productId) {
        return withTimeout(productId, () -> delegate.findSimilarIds(productId));
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        return withTimeout(productId, () -> delegate.findById(productId));
    }

    private <T> T withTimeout(ProductId productId, Supplier<T> lookup) {
        CompletableFuture<T> result = CompletableFuture.supplyAsync(lookup, executor);
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ProductCatalogUnavailableException(productId,
                    new TimeoutException("No answer within " + timeout.toMillis() + " ms"));
        } catch (ExecutionException e) {
            // Rethrow the delegate's exception as it is, so callers see the same exceptions as without this decorator
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new ProductCatalogUnavailableException(productId, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProductCatalogUnavailableException(productId, e);
        }
    }
}
