package com.inditex.similarproducts.infrastructure.resilience;

import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.application.port.ProductCatalogBusyException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Decorator that caps how many calls to another ProductCatalog run at the same time, to protect the
 * external product APIs during traffic spikes. A call that cannot get a slot in time is reported as
 * {@link ProductCatalogBusyException}, which circuit breakers ignore: it is our own load, not an upstream failure.
 */
public class BulkheadProductCatalog implements ProductCatalog {

    private final ProductCatalog delegate;
    private final BulkheadRegistry registry;
    private final Bulkhead bulkhead;

    public BulkheadProductCatalog(ProductCatalog delegate, BulkheadSettings settings) {
        this.delegate = delegate;
        this.registry = BulkheadRegistry.of(settings.toConfig());
        this.bulkhead = registry.bulkhead("product-api");
    }

    @Override
    public Optional<List<ProductId>> findSimilarIds(ProductId productId) {
        return call(productId, () -> delegate.findSimilarIds(productId));
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        return call(productId, () -> delegate.findById(productId));
    }

    public void registerMetrics(MeterRegistry meterRegistry) {
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meterRegistry);
    }

    private <T> T call(ProductId productId, Supplier<T> lookup) {
        try {
            return bulkhead.executeSupplier(lookup);
        } catch (BulkheadFullException e) {
            throw new ProductCatalogBusyException(productId, e);
        }
    }
}
