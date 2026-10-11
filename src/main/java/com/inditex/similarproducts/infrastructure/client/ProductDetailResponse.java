package com.inditex.similarproducts.infrastructure.client;

import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;

import java.math.BigDecimal;

/**
 * JSON shape of the external "GET /product/{productId}" response.
 * {@code availability} is a wrapper type so a missing value is detected instead of defaulting to false.
 */
record ProductDetailResponse(String id, String name, BigDecimal price, Boolean availability) {

    /**
     * @throws IllegalArgumentException if the response is not a valid product
     */
    Product toDomain() {
        if (availability == null) {
            throw new IllegalArgumentException("Product availability must not be null");
        }
        return new Product(new ProductId(id), name, price, availability);
    }
}
