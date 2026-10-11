package com.inditex.similarproducts.support;

import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;

import java.math.BigDecimal;

/**
 * Shorthands for building domain objects in tests.
 */
public final class TestProducts {

    private TestProducts() {
    }

    public static ProductId id(String value) {
        return new ProductId(value);
    }

    public static Product product(String id) {
        return new Product(id(id), "Product " + id, BigDecimal.TEN, true);
    }
}
