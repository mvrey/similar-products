package com.inditex.similarproducts.infrastructure.cache;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Duration;

/**
 * Cache settings.
 *
 * @param ttl          how long a found product (or list of similar ids) stays cached
 * @param notFoundTtl  how long the answer "this product does not exist" stays cached
 * @param refreshAfter age after which an entry that is read is reloaded in the background (shorter than ttl)
 * @param maximumSize  maximum number of entries per cache
 */
public record CacheSettings(@NotNull Duration ttl, @NotNull Duration notFoundTtl, @NotNull Duration refreshAfter,
                            @Positive long maximumSize) {
}
