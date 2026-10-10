package com.inditex.similarproducts.application;

import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import com.inditex.similarproducts.domain.port.ProductCatalog;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Returns the products similar to a given one, ordered by similarity.
 * Product details are fetched in parallel; similar products that cannot be retrieved are skipped.
 */
@Service
public class GetSimilarProductsUseCase {

    private final ProductCatalog productCatalog;
    private final Executor executor;

    public GetSimilarProductsUseCase(ProductCatalog productCatalog, Executor productLookupExecutor) {
        this.productCatalog = productCatalog;
        this.executor = productLookupExecutor;
    }

    public List<Product> execute(ProductId productId) {
        List<CompletableFuture<Optional<Product>>> lookups = productCatalog.findSimilarIds(productId).stream()
                .distinct()
                .map(id -> CompletableFuture.supplyAsync(() -> productCatalog.findById(id), executor))
                .toList();

        return lookups.stream()
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .toList();
    }
}
