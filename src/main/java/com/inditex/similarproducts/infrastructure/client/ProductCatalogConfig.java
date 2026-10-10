package com.inditex.similarproducts.infrastructure.client;

import com.inditex.similarproducts.domain.port.ProductCatalog;
import com.inditex.similarproducts.infrastructure.cache.CachingProductCatalog;
import com.inditex.similarproducts.infrastructure.cache.ProductCatalogProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.concurrent.Executor;

/**
 * Wires the ProductCatalog port: an HTTP adapter wrapped in a caching decorator.
 */
@Configuration
@EnableConfigurationProperties({ProductApiProperties.class, ProductCatalogProperties.class})
class ProductCatalogConfig {

    @Bean
    ProductCatalog productCatalog(RestClient.Builder restClientBuilder, ProductApiProperties apiProperties,
                                  ProductCatalogProperties catalogProperties, Executor productLookupExecutor) {
        ProductCatalog httpCatalog = new HttpProductCatalog(productApiRestClient(restClientBuilder, apiProperties));

        return new CachingProductCatalog(
                httpCatalog,
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
