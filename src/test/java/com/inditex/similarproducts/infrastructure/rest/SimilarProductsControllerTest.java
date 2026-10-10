package com.inditex.similarproducts.infrastructure.rest;

import com.inditex.similarproducts.application.GetSimilarProductsUseCase;
import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.exception.ProductNotFoundException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SimilarProductsController.class)
class SimilarProductsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetSimilarProductsUseCase getSimilarProducts;

    @Test
    void returnsSimilarProductsAsJson() throws Exception {
        when(getSimilarProducts.execute(new ProductId("1"))).thenReturn(List.of(
                new Product(new ProductId("2"), "Dress", new BigDecimal("19.99"), true),
                new Product(new ProductId("3"), "Blazer", new BigDecimal("29.99"), false)));

        mockMvc.perform(get("/product/1/similar"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        [
                          {"id":"2","name":"Dress","price":19.99,"availability":true},
                          {"id":"3","name":"Blazer","price":29.99,"availability":false}
                        ]
                        """, true));
    }

    @Test
    void returnsEmptyArrayWhenThereAreNoSimilarProducts() throws Exception {
        when(getSimilarProducts.execute(new ProductId("1"))).thenReturn(List.of());

        mockMvc.perform(get("/product/1/similar"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void returns404WhenProductDoesNotExist() throws Exception {
        when(getSimilarProducts.execute(new ProductId("99")))
                .thenThrow(new ProductNotFoundException(new ProductId("99")));

        mockMvc.perform(get("/product/99/similar"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Product not found"));
    }

    @Test
    void returns502WhenCatalogIsUnavailable() throws Exception {
        when(getSimilarProducts.execute(new ProductId("1")))
                .thenThrow(new ProductCatalogUnavailableException(new ProductId("1"), new RuntimeException("boom")));

        mockMvc.perform(get("/product/1/similar"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502));
    }
}
