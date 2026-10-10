package com.inditex.similarproducts.domain.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A product of the catalog, as needed by the similar products feature.
 */
public record Product(ProductId id, String name, BigDecimal price, boolean availability) {

    public Product {
        Objects.requireNonNull(id, "Product id must not be null");
        Objects.requireNonNull(name, "Product name must not be null");
        Objects.requireNonNull(price, "Product price must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Product name must not be blank");
        }
    }
}
