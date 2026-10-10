package com.inditex.similarproducts.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    @Test
    void createsValidProduct() {
        Product product = new Product(new ProductId("1"), "Shirt", new BigDecimal("9.99"), true);

        assertThat(product.id()).isEqualTo(new ProductId("1"));
        assertThat(product.name()).isEqualTo("Shirt");
        assertThat(product.price()).isEqualByComparingTo("9.99");
        assertThat(product.availability()).isTrue();
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new Product(new ProductId("1"), " ", BigDecimal.ONE, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingPrice() {
        assertThatThrownBy(() -> new Product(new ProductId("1"), "Shirt", null, true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankId() {
        assertThatThrownBy(() -> new ProductId(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsWithSameValueAreEqual() {
        assertThat(new ProductId("42")).isEqualTo(new ProductId("42"));
    }
}
