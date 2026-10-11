package com.inditex.similarproducts.infrastructure.client;

import com.inditex.similarproducts.domain.port.ProductCatalog;
import com.inditex.similarproducts.infrastructure.cache.CachingProductCatalog;
import com.inditex.similarproducts.infrastructure.cache.ProductCatalogProperties;
import com.inditex.similarproducts.infrastructure.resilience.BulkheadProductCatalog;
import com.inditex.similarproducts.infrastructure.resilience.BulkheadProperties;
import com.inditex.similarproducts.infrastructure.resilience.CircuitBreakerProperties;
import com.inditex.similarproducts.infrastructure.resilience.CircuitBreakingProductCatalog;
import com.inditex.similarproducts.infrastructure.resilience.TimeLimitingProductCatalog;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executor;

/**
 * Wires the ProductCatalog port as a chain of decorators:
 * time limit -> cache -> bulkhead -> circuit breakers -> HTTP adapter -> external product APIs.
 */
@Configuration
@EnableConfigurationProperties({ProductApiProperties.class, ProductCatalogProperties.class,
        CircuitBreakerProperties.class, BulkheadProperties.class})
class ProductCatalogConfig {

    @Bean
    ProductCatalog productCatalog(RestClient.Builder restClientBuilder, ProductApiProperties apiProperties,
                                  ProductCatalogProperties catalogProperties,
                                  CircuitBreakerProperties circuitBreakerProperties,
                                  BulkheadProperties bulkheadProperties, Executor productLookupExecutor) {
        ProductCatalog catalog = HttpProductCatalog.create(restClientBuilder, apiProperties);
        catalog = new CircuitBreakingProductCatalog(catalog, circuitBreakerProperties.toConfig());
        // Above the breakers, so calls rejected because of our own load never count as upstream failures
        catalog = new BulkheadProductCatalog(catalog, bulkheadProperties.toConfig());
        catalog = new CachingProductCatalog(catalog, catalogProperties.cache().ttl(),
                catalogProperties.cache().maximumSize(), productLookupExecutor);
        return new TimeLimitingProductCatalog(catalog, catalogProperties.lookupTimeout(), productLookupExecutor);
    }
}
