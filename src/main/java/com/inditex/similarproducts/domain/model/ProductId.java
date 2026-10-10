package com.inditex.similarproducts.domain.model;

import java.util.Objects;

/**
 * Identifier of a product. A value object: two ProductIds with the same value are equal.
 */
public record ProductId(String value) {

    public ProductId {
        Objects.requireNonNull(value, "Product id must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Product id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
