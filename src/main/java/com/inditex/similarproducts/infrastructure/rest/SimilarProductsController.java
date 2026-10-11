package com.inditex.similarproducts.infrastructure.rest;

import com.inditex.similarproducts.application.GetSimilarProductsUseCase;
import com.inditex.similarproducts.domain.model.ProductId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
class SimilarProductsController {

    private final GetSimilarProductsUseCase getSimilarProducts;
    private final CacheControl cacheControl;

    /**
     * @param responseMaxAge how long clients may reuse a response (it is served from a cache anyway)
     */
    SimilarProductsController(GetSimilarProductsUseCase getSimilarProducts,
                              @Value("${similar-products.response-max-age}") Duration responseMaxAge) {
        this.getSimilarProducts = getSimilarProducts;
        this.cacheControl = CacheControl.maxAge(responseMaxAge);
    }

    @GetMapping("/product/{productId}/similar")
    ResponseEntity<List<ProductResponse>> getSimilarProducts(@PathVariable String productId) {
        List<ProductResponse> similarProducts = getSimilarProducts.execute(new ProductId(productId)).stream()
                .map(ProductResponse::from)
                .toList();
        return ResponseEntity.ok().cacheControl(cacheControl).body(similarProducts);
    }
}
