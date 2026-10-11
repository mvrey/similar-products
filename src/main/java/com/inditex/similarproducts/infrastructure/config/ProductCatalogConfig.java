package com.inditex.similarproducts.infrastructure.config;

import com.inditex.similarproducts.application.GetSimilarProductsUseCase;
import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.infrastructure.cache.CachingProductCatalog;
import com.inditex.similarproducts.infrastructure.client.HttpProductCatalog;
import com.inditex.similarproducts.infrastructure.client.ProductApiProperties;
import com.inditex.similarproducts.infrastructure.resilience.BulkheadProductCatalog;
import com.inditex.similarproducts.infrastructure.resilience.PerProductCircuitBreakingCatalog;
import com.inditex.similarproducts.infrastructure.resilience.SimilarIdsCircuitBreakingCatalog;
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
 * per-product breakers -> time limit -> cache -> bulkhead -> similar ids breaker -> HTTP adapter.
 * The order matters; see the comments below.
 * <p>
 * Metrics are registered explicitly. The decorators deliberately don't implement Micrometer's MeterBinder:
 * Spring Boot binds MeterBinder beans by itself, so the outermost one (the bean) would be registered twice.
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

        // Below the cache: cached similar ids are still served while this breaker is open
        SimilarIdsCircuitBreakingCatalog similarIdsBreaker = new SimilarIdsCircuitBreakingCatalog(http,
                properties.circuitBreaker());
        similarIdsBreaker.registerMetrics(meterRegistry);

        // Above the similar ids breaker: calls rejected because of our own load never reach it
        BulkheadProductCatalog bulkhead = new BulkheadProductCatalog(similarIdsBreaker, properties.bulkhead());
        bulkhead.registerMetrics(meterRegistry);

        // Below the time limit: a slow upstream call keeps running after the caller gives up, and fills the cache
        CachingProductCatalog caching = new CachingProductCatalog(bulkhead, properties.cache(),
                productLookupExecutor);
        caching.registerMetrics(meterRegistry);

        TimeLimitingProductCatalog timeLimited = new TimeLimitingProductCatalog(caching,
                properties.lookupTimeout(), productLookupExecutor);

        // Above the time limit: a product that keeps timing out opens its breaker and is then skipped at once
        PerProductCircuitBreakingCatalog perProductBreakers = new PerProductCircuitBreakingCatalog(timeLimited,
                properties.circuitBreaker());
        perProductBreakers.registerMetrics(meterRegistry);
        return perProductBreakers;
    }
}
