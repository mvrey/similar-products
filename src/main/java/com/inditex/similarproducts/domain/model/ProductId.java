package com.inditex.similarproducts.domain.model;

import com.inditex.similarproducts.domain.exception.InvalidProductIdException;

/**
 * Identifier of a product. A value object: two ProductIds with the same value are equal.
 * Control characters are rejected: no real id contains them, and they would let a caller forge log lines.
 */
public record ProductId(String value) {

    public ProductId {
        if (value == null || value.isBlank()) {
            throw new InvalidProductIdException("Product id must not be blank");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidProductIdException("Product id must not contain control characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
