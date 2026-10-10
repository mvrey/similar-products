package com.inditex.similarproducts.domain.exception;

import com.inditex.similarproducts.domain.model.ProductId;

public class ProductCatalogUnavailableException extends RuntimeException {

    public ProductCatalogUnavailableException(ProductId productId, Throwable cause) {
        super("Product catalog unavailable while looking up product " + productId, cause);
    }
}
