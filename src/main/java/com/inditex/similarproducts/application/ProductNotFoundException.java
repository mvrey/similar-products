package com.inditex.similarproducts.application;

import com.inditex.similarproducts.domain.model.ProductId;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(ProductId productId) {
        super("Product not found: " + productId);
    }
}
