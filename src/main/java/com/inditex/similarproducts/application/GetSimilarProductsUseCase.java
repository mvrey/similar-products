package com.inditex.similarproducts.application;

import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import com.inditex.similarproducts.domain.port.ProductCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(GetSimilarProductsUseCase.class);

    private final ProductCatalog productCatalog;
    private final Executor executor;

    public GetSimilarProductsUseCase(ProductCatalog productCatalog, Executor productLookupExecutor) {
        this.productCatalog = productCatalog;
        this.executor = productLookupExecutor;
    }

    public List<Product> execute(ProductId productId) {
        List<CompletableFuture<Optional<Product>>> lookups = productCatalog.findSimilarIds(productId).stream()
                .distinct()
                .map(id -> CompletableFuture.supplyAsync(() -> findSimilarProduct(id), executor))
                .toList();

        return lookups.stream()
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * A similar product that cannot be retrieved right now is left out rather than failing the whole response.
     */
    private Optional<Product> findSimilarProduct(ProductId id) {
        try {
            return productCatalog.findById(id);
        } catch (ProductCatalogUnavailableException e) {
            log.warn("Skipping similar product {}: {}", id, rootCauseMessage(e));
            return Optional.empty();
        }
    }

    private static String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
