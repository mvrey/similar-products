package com.inditex.similarproducts.infrastructure.rest;

import com.inditex.similarproducts.domain.model.Product;

import java.math.BigDecimal;

/**
 * JSON shape of a product in our API, as defined by the ProductDetail schema in similarProducts.yaml.
 */
record ProductResponse(String id, String name, BigDecimal price, boolean availability) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.id().value(), product.name(), product.price(), product.availability());
    }
}
