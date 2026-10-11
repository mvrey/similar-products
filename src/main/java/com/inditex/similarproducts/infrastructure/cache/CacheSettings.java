package com.inditex.similarproducts.infrastructure.cache;

import java.time.Duration;

/**
 * Cache settings.
 *
 * @param ttl          how long a found product (or list of similar ids) stays cached
 * @param notFoundTtl  how long the answer "this product does not exist" stays cached
 * @param refreshAfter age after which an entry that is read is reloaded in the background (shorter than ttl)
 * @param maximumSize  maximum number of entries per cache
 */
public record CacheSettings(Duration ttl, Duration notFoundTtl, Duration refreshAfter, long maximumSize) {
}
