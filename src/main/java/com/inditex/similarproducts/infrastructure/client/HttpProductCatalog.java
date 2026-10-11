package com.inditex.similarproducts.infrastructure.client;

import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.application.port.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Adapter that implements the ProductCatalog port by calling the existing product APIs over HTTP.
 */
public class HttpProductCatalog implements ProductCatalog {

    private static final Logger log = LoggerFactory.getLogger(HttpProductCatalog.class);

    private final RestClient restClient;

    HttpProductCatalog(RestClient productApiRestClient) {
        this.restClient = productApiRestClient;
    }

    /**
     * Creates an adapter whose HTTP client applies the configured base URL and timeouts.
     */
    public static HttpProductCatalog create(RestClient.Builder builder, ProductApiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return new HttpProductCatalog(builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build());
    }

    @Override
    public Optional<List<ProductId>> findSimilarIds(ProductId productId) {
        try {
            String[] ids = restClient.get()
                    .uri("/product/{productId}/similarids", productId.value())
                    .retrieve()
                    .body(String[].class);

            if (ids == null) {
                return Optional.of(List.of());
            }
            return Optional.of(Arrays.stream(ids).map(ProductId::new).toList());
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Product {} not found", productId);
            return Optional.empty();
        } catch (RestClientException | IllegalArgumentException e) {
            // Upstream error, timeout or an invalid id in the response
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
        } catch (RestClientException | IllegalArgumentException e) {
            // Upstream error, timeout or a response that is not a valid product
            throw new ProductCatalogUnavailableException(productId, e);
        }
    }
}
