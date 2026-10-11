package com.inditex.similarproducts.application;

import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.application.port.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Returns the products similar to a given one, ordered by similarity.
 * Product details are fetched in parallel; similar products that cannot be retrieved are skipped.
 */
public class GetSimilarProductsUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetSimilarProductsUseCase.class);

    private final ProductCatalog productCatalog;
    private final Executor executor;

    public GetSimilarProductsUseCase(ProductCatalog productCatalog, Executor executor) {
        this.productCatalog = productCatalog;
        this.executor = executor;
    }

    /**
     * @throws ProductNotFoundException if the product does not exist
     * @throws ProductCatalogUnavailableException if its similar products cannot be determined
     */
    public List<Product> execute(ProductId productId) {
        List<ProductId> similarIds = productCatalog.findSimilarIds(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        List<CompletableFuture<Optional<Product>>> lookups = similarIds.stream()
                .distinct()
                .map(id -> CompletableFuture.supplyAsync(() -> findSimilarProduct(id), executor))
                .toList();

        return lookups.stream()
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * A similar product that does not exist or cannot be retrieved right now is left out rather than
     * failing the whole response.
     */
    private Optional<Product> findSimilarProduct(ProductId id) {
        try {
            return productCatalog.findById(id);
        } catch (ProductCatalogUnavailableException e) {
            // Expected and frequent (e.g. a product that always fails): circuit breakers log the state changes
            log.debug("Skipping similar product: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
