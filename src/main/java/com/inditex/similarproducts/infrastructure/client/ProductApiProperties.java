package com.inditex.similarproducts.infrastructure.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Connection settings for the external product APIs, bound from the "product-api.*" properties.
 */
@Validated
@ConfigurationProperties(prefix = "product-api")
public record ProductApiProperties(@NotBlank String baseUrl, @NotNull Duration connectTimeout,
                                   @NotNull Duration readTimeout) {
}
