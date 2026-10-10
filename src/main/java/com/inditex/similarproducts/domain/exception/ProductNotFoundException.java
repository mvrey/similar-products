package com.inditex.similarproducts.domain.exception;

import com.inditex.similarproducts.domain.model.ProductId;

public class ProductNotFoundException extends RuntimeException {

    private final ProductId productId;

    public ProductNotFoundException(ProductId productId) {
        super("Product not found: " + productId);
        this.productId = productId;
    }

    public ProductId getProductId() {
        return productId;
    }
}
