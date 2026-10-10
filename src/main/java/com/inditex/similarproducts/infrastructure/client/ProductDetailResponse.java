package com.inditex.similarproducts.infrastructure.client;

import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;

import java.math.BigDecimal;

/**
 * JSON shape of the external "GET /product/{productId}" response.
 */
record ProductDetailResponse(String id, String name, BigDecimal price, boolean availability) {

    Product toDomain() {
        return new Product(new ProductId(id), name, price, availability);
    }
}
