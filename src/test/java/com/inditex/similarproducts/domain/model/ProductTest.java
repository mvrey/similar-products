package com.inditex.similarproducts.domain.model;

import com.inditex.similarproducts.domain.exception.InvalidProductIdException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new Product(new ProductId("1"), " ", BigDecimal.ONE, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingPrice() {
        assertThatThrownBy(() -> new Product(new ProductId("1"), "Shirt", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankId() {
        assertThatThrownBy(() -> new ProductId(" "))
                .isInstanceOf(InvalidProductIdException.class);
    }

    @Test
    void rejectsIdsWithControlCharacters() {
        assertThatThrownBy(() -> new ProductId("1\nFAKE LOG LINE"))
                .isInstanceOf(InvalidProductIdException.class);
    }

    @Test
    void rejectsMissingId() {
        assertThatThrownBy(() -> new ProductId(null))
                .isInstanceOf(InvalidProductIdException.class);
    }
}
