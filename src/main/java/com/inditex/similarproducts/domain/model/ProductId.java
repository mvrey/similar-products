package com.inditex.similarproducts.domain.model;

import com.inditex.similarproducts.domain.exception.InvalidProductIdException;

/**
 * Identifier of a product. A value object: two ProductIds with the same value are equal.
 */
public record ProductId(String value) {

    public ProductId {
        if (value == null || value.isBlank()) {
            throw new InvalidProductIdException("Product id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
