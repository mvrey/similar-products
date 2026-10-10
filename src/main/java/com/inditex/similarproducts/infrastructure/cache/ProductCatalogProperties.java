package com.inditex.similarproducts.infrastructure.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Settings for product lookups, bound from the "product-catalog.*" properties.
 *
 * @param lookupTimeout how long a request waits for a product before skipping it
 * @param cache         cache settings for successful lookups
 */
@ConfigurationProperties(prefix = "product-catalog")
public record ProductCatalogProperties(Duration lookupTimeout, Cache cache) {

    public record Cache(Duration ttl, long maximumSize) {
    }
}
