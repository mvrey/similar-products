package com.inditex.similarproducts.infrastructure.client;

import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.exception.ProductNotFoundException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import com.inditex.similarproducts.domain.port.ProductCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Adapter that implements the ProductCatalog port by calling the existing product APIs over HTTP.
 */
class HttpProductCatalog implements ProductCatalog {

    private static final Logger log = LoggerFactory.getLogger(HttpProductCatalog.class);

    private final RestClient restClient;

    HttpProductCatalog(RestClient productApiRestClient) {
        this.restClient = productApiRestClient;
    }

    @Override
    public List<ProductId> findSimilarIds(ProductId productId) {
        try {
            String[] ids = restClient.get()
                    .uri("/product/{productId}/similarids", productId.value())
                    .retrieve()
                    .body(String[].class);

            if (ids == null) {
                return List.of();
            }
            return Arrays.stream(ids).map(ProductId::new).toList();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ProductNotFoundException(productId);
        } catch (RestClientException e) {
            throw new ProductCatalogUnavailableException(productId, e);
        }
    }

    @Override
    public Optional<Product> findById(ProductId productId) {
        try {
            ProductDetailResponse response = restClient.get()
                    .uri("/product/{productId}", productId.value())
                    .retrieve()
                    .body(ProductDetailResponse.class);

            return Optional.ofNullable(response).map(ProductDetailResponse::toDomain);
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Product {} not found", productId);
            return Optional.empty();
        } catch (RestClientException | IllegalArgumentException | NullPointerException e) {
            // Upstream error, timeout or a response that is not a valid product
            throw new ProductCatalogUnavailableException(productId, e);
        }
    }
}
