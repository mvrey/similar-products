package com.inditex.similarproducts.application.port;

import com.inditex.similarproducts.domain.model.ProductId;

import java.util.concurrent.TimeoutException;

/**
 * The product catalog did not answer in time. A special case of being unavailable.
 */
public class ProductCatalogTimeoutException extends ProductCatalogUnavailableException {

    public ProductCatalogTimeoutException(ProductId productId, TimeoutException cause) {
        super(productId, cause);
    }
}
