package com.inditex.similarproducts.infrastructure.resilience;

import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import com.inditex.similarproducts.domain.port.ProductCatalog;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Decorator that caps how many calls to another ProductCatalog run at the same time, to protect the
 * external product APIs during traffic spikes. A call that cannot get a slot in time is reported as
 * unavailable.
 */
public class BulkheadProductCatalog implements ProductCatalog {

    private final ProductCatalog delegate;
    private final Bulkhead bulkhead;

    public BulkheadProductCatalog(ProductCatalog delegate, BulkheadConfig config) {
        this.delegate = delegate;
        this.bulkhead = Bulkhead.of("product-api", config);
    }

    @Override
    public Optional<List<ProductId>> findSimilarIds(ProductId productId) {
        return call(productId, () -> delegate.findSimilarIds(productId));
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        return call(productId, () -> delegate.findById(productId));
    }

    private <T> T call(ProductId productId, Supplier<T> lookup) {
        try {
            return bulkhead.executeSupplier(lookup);
        } catch (BulkheadFullException e) {
            throw new ProductCatalogUnavailableException(productId, e);
        }
    }
}
