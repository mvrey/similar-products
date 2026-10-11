package com.inditex.similarproducts.infrastructure.cache;

import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.application.port.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static com.inditex.similarproducts.support.TestProducts.id;
import static com.inditex.similarproducts.support.TestProducts.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachingProductCatalogTest {

    private static final CacheSettings SETTINGS =
            new CacheSettings(Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(8), 100);

    private final ProductCatalog delegate = mock(ProductCatalog.class);
    private final AtomicLong nanoTime = new AtomicLong();
    // Slow upstream calls wait on this until the test lets them finish
    private final CountDownLatch upstreamReleased = new CountDownLatch(1);
    private ExecutorService executor;
    private CachingProductCatalog catalog;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        // Loads run on the calling thread, so a load (and Caffeine's bookkeeping of its write time) has
        // completed before a test moves the clock
        catalog = new CachingProductCatalog(delegate, SETTINGS, Runnable::run, nanoTime::get);
    }

    @AfterEach
    void tearDown() {
        upstreamReleased.countDown();
        executor.close();
    }

    @Test
    void cachesProductDetails() {
        when(delegate.findById(id("1"))).thenReturn(Optional.of(product("1")));

        assertThat(catalog.findById(id("1"))).contains(product("1"));
        assertThat(catalog.findById(id("1"))).contains(product("1"));

        verify(delegate, times(1)).findById(id("1"));
    }

    @Test
    void looksUpProductAgainOnceTheEntryExpires() {
        when(delegate.findById(id("1"))).thenReturn(Optional.of(product("1")));

        catalog.findById(id("1"));
        advanceTime(SETTINGS.ttl().plusSeconds(1));
        catalog.findById(id("1"));

        verify(delegate, times(2)).findById(id("1"));
    }

    @Test
    void refreshesAnAgingEntryInTheBackgroundWhileServingTheCachedValue() {
        // The first load runs on the calling thread; the refresh on a background thread, held until released
        AtomicBoolean loadInline = new AtomicBoolean(true);
        Executor switchingExecutor = task -> {
            if (loadInline.get()) {
                task.run();
            } else {
                executor.execute(task);
            }
        };
        CachingProductCatalog refreshingCatalog =
                new CachingProductCatalog(delegate, SETTINGS, switchingExecutor, nanoTime::get);
        Product before = new Product(id("1"), "Before", BigDecimal.TEN, true);
        Product after = new Product(id("1"), "After", BigDecimal.ONE, true);
        when(delegate.findById(id("1")))
                .thenReturn(Optional.of(before))
                .thenAnswer(invocation -> {
                    upstreamReleased.await();
                    return Optional.of(after);
                });

        refreshingCatalog.findById(id("1"));
        loadInline.set(false);
        advanceTime(SETTINGS.refreshAfter().plusSeconds(1));

        // While the reload is in flight, reads are still served the cached value at once...
        assertThat(refreshingCatalog.findById(id("1"))).contains(before);
        assertThat(refreshingCatalog.findById(id("1"))).contains(before);

        // ...until the reload completes and replaces it
        upstreamReleased.countDown();
        await().atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(refreshingCatalog.findById(id("1"))).contains(after));
        verify(delegate, times(2)).findById(id("1"));
    }

    @Test
    void cachesMissingProductsBriefly() {
        when(delegate.findById(id("5"))).thenReturn(Optional.empty());

        assertThat(catalog.findById(id("5"))).isEmpty();
        assertThat(catalog.findById(id("5"))).isEmpty();
        verify(delegate, times(1)).findById(id("5"));

        advanceTime(SETTINGS.notFoundTtl().plusSeconds(1));
        assertThat(catalog.findById(id("5"))).isEmpty();
        verify(delegate, times(2)).findById(id("5"));
    }

    @Test
    void doesNotCacheFailures() {
        when(delegate.findById(id("6")))
                .thenThrow(new ProductCatalogUnavailableException(id("6"), new RuntimeException("500")))
                .thenReturn(Optional.of(product("6")));

        assertThatThrownBy(() -> catalog.findById(id("6")))
                .isInstanceOf(ProductCatalogUnavailableException.class);
        assertThat(catalog.findById(id("6"))).contains(product("6"));

        verify(delegate, times(2)).findById(id("6"));
    }

    @Test
    void cachesSimilarIds() {
        when(delegate.findSimilarIds(id("1"))).thenReturn(Optional.of(List.of(id("2"), id("3"))));

        assertThat(catalog.findSimilarIds(id("1"))).contains(List.of(id("2"), id("3")));
        assertThat(catalog.findSimilarIds(id("1"))).contains(List.of(id("2"), id("3")));

        verify(delegate, times(1)).findSimilarIds(id("1"));
    }

    @Test
    void cachesSimilarIdsOfMissingProductsBriefly() {
        when(delegate.findSimilarIds(id("99"))).thenReturn(Optional.empty());

        assertThat(catalog.findSimilarIds(id("99"))).isEmpty();
        assertThat(catalog.findSimilarIds(id("99"))).isEmpty();

        verify(delegate, times(1)).findSimilarIds(id("99"));
    }

    @Test
    void concurrentRequestsShareOneUpstreamCall() {
        CachingProductCatalog asyncCatalog = new CachingProductCatalog(delegate, SETTINGS, executor, nanoTime::get);
        when(delegate.findById(id("1"))).thenAnswer(invocation -> {
            upstreamReleased.await();
            return Optional.of(product("1"));
        });

        List<CompletableFuture<Optional<Product>>> requests = IntStream.range(0, 50)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> asyncCatalog.findById(id("1")), executor))
                .toList();

        // While the upstream call is still in flight, the waiting requests have not started any other
        verify(delegate, after(100).times(1)).findById(id("1"));
        upstreamReleased.countDown();
        assertThat(requests).allSatisfy(request -> assertThat(request.join()).contains(product("1")));
        verify(delegate, times(1)).findById(id("1"));
    }

    @Test
    void publishesHitsAndMissesAsMetrics() {
        when(delegate.findById(id("1"))).thenReturn(Optional.of(product("1")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        catalog.registerMetrics(registry);

        catalog.findById(id("1"));
        catalog.findById(id("1"));

        assertThat(registry.get("cache.gets").tags("cache", "products", "result", "miss").functionCounter().count())
                .isEqualTo(1);
        assertThat(registry.get("cache.gets").tags("cache", "products", "result", "hit").functionCounter().count())
                .isEqualTo(1);
    }

    private void advanceTime(Duration duration) {
        nanoTime.addAndGet(duration.toNanos());
    }
}
