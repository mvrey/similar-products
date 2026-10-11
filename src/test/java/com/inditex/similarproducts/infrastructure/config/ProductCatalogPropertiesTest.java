package com.inditex.similarproducts.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProductCatalogPropertiesTest {

    // Binds the real application.properties
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(EnableProperties.class);

    @Test
    void bindsTheApplicationSettings() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ProductCatalogProperties properties = context.getBean(ProductCatalogProperties.class);
            assertThat(properties.lookupTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.cache().notFoundTtl()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.bulkhead().maxConcurrentCalls()).isEqualTo(200);
        });
    }

    @Test
    void refusesToStartWithAnInvalidSetting() {
        runner.withPropertyValues("product-catalog.bulkhead.max-concurrent-calls=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("maxConcurrentCalls");
        });
    }

    @Test
    void refusesToStartWithAMissingSetting() {
        runner.withPropertyValues("product-catalog.cache.ttl=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("ttl");
        });
    }

    @Configuration
    @EnableConfigurationProperties(ProductCatalogProperties.class)
    static class EnableProperties {
    }
}
