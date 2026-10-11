package com.inditex.similarproducts.application.port;

import com.inditex.similarproducts.domain.model.ProductId;

/**
 * The call was refused because too many calls to the product catalog are already running. It says that this
 * service is overloaded, not that the catalog is failing. A special case of being unavailable.
 */
public class ProductCatalogBusyException extends ProductCatalogUnavailableException {

    public ProductCatalogBusyException(ProductId productId, Throwable cause) {
        super(productId, cause);
    }
}
