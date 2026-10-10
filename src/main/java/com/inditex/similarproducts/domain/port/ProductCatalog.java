package com.inditex.similarproducts.domain.port;

import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.exception.ProductNotFoundException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port: what the domain needs from the external product services.
 * The implementation (an HTTP adapter) lives in the infrastructure layer.
 */
public interface ProductCatalog {

    /**
     * Returns the ids of the products similar to the given one, ordered by similarity.
     *
     * @throws ProductNotFoundException if the product does not exist
     * @throws ProductCatalogUnavailableException if the catalog cannot be reached
     */
    List<ProductId> findSimilarIds(ProductId productId);

    /**
     * Returns the detail of a product, or empty if it does not exist.
     *
     * @throws ProductCatalogUnavailableException if the product cannot be retrieved
     */
    Optional<Product> findById(ProductId productId);
}
