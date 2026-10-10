package com.inditex.similarproducts.infrastructure.rest;

import com.inditex.similarproducts.application.GetSimilarProductsUseCase;
import com.inditex.similarproducts.domain.model.ProductId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
class SimilarProductsController {

    private final GetSimilarProductsUseCase getSimilarProducts;

    SimilarProductsController(GetSimilarProductsUseCase getSimilarProducts) {
        this.getSimilarProducts = getSimilarProducts;
    }

    @GetMapping("/product/{productId}/similar")
    List<ProductResponse> getSimilarProducts(@PathVariable String productId) {
        return getSimilarProducts.execute(new ProductId(productId)).stream()
                .map(ProductResponse::from)
                .toList();
    }
}
