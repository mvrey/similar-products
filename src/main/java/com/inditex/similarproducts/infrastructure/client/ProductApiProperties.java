package com.inditex.similarproducts.infrastructure.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection settings for the external product APIs, bound from the "product-api.*" properties.
 */
@ConfigurationProperties(prefix = "product-api")
public record ProductApiProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
