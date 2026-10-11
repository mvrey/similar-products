package com.inditex.similarproducts.infrastructure.config;

import com.inditex.similarproducts.application.GetSimilarProductsUseCase;
import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.infrastructure.cache.CachingProductCatalog;
import com.inditex.similarproducts.infrastructure.client.HttpProductCatalog;
import com.inditex.similarproducts.infrastructure.client.ProductApiProperties;
import com.inditex.similarproducts.infrastructure.resilience.BulkheadProductCatalog;
import com.inditex.similarproducts.infrastructure.resilience.CircuitBreakingProductCatalog;
import com.inditex.similarproducts.infrastructure.resilience.TimeLimitingProductCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executor;

/**
 * Composition root: builds the use case and the ProductCatalog it depends on. The application and domain
 * layers carry no Spring annotations; everything is wired here.
 * <p>
 * The port is a chain of decorators:
 * time limit -> cache -> bulkhead -> circuit breakers -> HTTP adapter -> external product APIs.
 */
@Configuration
@EnableConfigurationProperties({ProductApiProperties.class, ProductCatalogProperties.class})
class ProductCatalogConfig {

    @Bean
    GetSimilarProductsUseCase getSimilarProductsUseCase(
            ProductCatalog productCatalog,
            @Qualifier(ConcurrencyConfig.PRODUCT_LOOKUP_EXECUTOR) Executor productLookupExecutor) {
        return new GetSimilarProductsUseCase(productCatalog, productLookupExecutor);
    }

    @Bean
    ProductCatalog productCatalog(RestClient.Builder restClientBuilder, ProductApiProperties apiProperties,
                                  ProductCatalogProperties properties, MeterRegistry meterRegistry,
                                  @Qualifier(ConcurrencyConfig.PRODUCT_LOOKUP_EXECUTOR) Executor productLookupExecutor) {
        HttpProductCatalog http = HttpProductCatalog.create(restClientBuilder, apiProperties);

        CircuitBreakingProductCatalog circuitBreaking = new CircuitBreakingProductCatalog(http,
                properties.circuitBreaker());
        circuitBreaking.bindTo(meterRegistry);

        // Above the breakers, so calls rejected because of our own load never count as upstream failures
        BulkheadProductCatalog bulkhead = new BulkheadProductCatalog(circuitBreaking, properties.bulkhead());
        bulkhead.bindTo(meterRegistry);

        CachingProductCatalog caching = new CachingProductCatalog(bulkhead, properties.cache(),
                productLookupExecutor);
        caching.bindTo(meterRegistry);

        return new TimeLimitingProductCatalog(caching, properties.lookupTimeout(), productLookupExecutor);
    }
}
