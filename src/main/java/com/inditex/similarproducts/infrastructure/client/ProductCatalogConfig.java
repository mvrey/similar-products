package com.inditex.similarproducts.infrastructure.client;

import com.inditex.similarproducts.domain.port.ProductCatalog;
import com.inditex.similarproducts.infrastructure.cache.CachingProductCatalog;
import com.inditex.similarproducts.infrastructure.cache.ProductCatalogProperties;
import com.inditex.similarproducts.infrastructure.resilience.CircuitBreakerProperties;
import com.inditex.similarproducts.infrastructure.resilience.CircuitBreakingProductCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.concurrent.Executor;

/**
 * Wires the ProductCatalog port as a chain of decorators:
 * cache -> circuit breakers -> HTTP adapter -> external product APIs.
 */
@Configuration
@EnableConfigurationProperties({ProductApiProperties.class, ProductCatalogProperties.class,
        CircuitBreakerProperties.class})
class ProductCatalogConfig {

    @Bean
    ProductCatalog productCatalog(RestClient.Builder restClientBuilder, ProductApiProperties apiProperties,
                                  ProductCatalogProperties catalogProperties,
                                  CircuitBreakerProperties circuitBreakerProperties, Executor productLookupExecutor) {
        ProductCatalog httpCatalog = new HttpProductCatalog(productApiRestClient(restClientBuilder, apiProperties));
        ProductCatalog protectedCatalog = new CircuitBreakingProductCatalog(httpCatalog,
                circuitBreakerProperties.toConfig());

        return new CachingProductCatalog(
                protectedCatalog,
                catalogProperties.lookupTimeout(),
                catalogProperties.cache().ttl(),
                catalogProperties.cache().maximumSize(),
                productLookupExecutor);
    }

    private static RestClient productApiRestClient(RestClient.Builder builder, ProductApiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
