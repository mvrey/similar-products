package com.inditex.similarproducts.application.port;

import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port of the application layer: what the use case needs from the external product services.
 * The implementations (an HTTP adapter and the decorators around it) live in the infrastructure layer.
 * <p>
 * Both operations answer "the product does not exist" with an empty result; what that means for the
 * request is decided by the caller.
 */
public interface ProductCatalog {

    /**
     * Returns the ids of the products similar to the given one, ordered by similarity,
     * or empty if the product does not exist.
     *
     * @throws ProductCatalogUnavailableException if the similar ids cannot be retrieved
     */
    Optional<List<ProductId>> findSimilarIds(ProductId productId);

    /**
     * Returns the detail of a product, or empty if it does not exist.
     *
     * @throws ProductCatalogUnavailableException if the product cannot be retrieved
     */
    Optional<Product> findById(ProductId productId);
}
