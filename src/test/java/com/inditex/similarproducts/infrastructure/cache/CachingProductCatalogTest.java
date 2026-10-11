package com.inditex.similarproducts.infrastructure.cache;

import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.exception.ProductNotFoundException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.port.ProductCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static com.inditex.similarproducts.support.TestProducts.id;
import static com.inditex.similarproducts.support.TestProducts.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachingProductCatalogTest {

    private static final Duration LOOKUP_TIMEOUT = Duration.ofMillis(100);
    private static final Duration TTL = Duration.ofMinutes(10);

    private final ProductCatalog delegate = mock(ProductCatalog.class);
    private final AtomicLong nanoTime = new AtomicLong();
    // Slow upstream calls wait on this until the test lets them finish
    private final CountDownLatch upstreamReleased = new CountDownLatch(1);
    private ExecutorService executor;
    private CachingProductCatalog catalog;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        catalog = newCatalog(LOOKUP_TIMEOUT);
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
        nanoTime.addAndGet(TTL.plusSeconds(1).toNanos());
        catalog.findById(id("1"));

        verify(delegate, times(2)).findById(id("1"));
    }

    @Test
    void doesNotCacheMissingProducts() {
        when(delegate.findById(id("5"))).thenReturn(Optional.empty());

        assertThat(catalog.findById(id("5"))).isEmpty();
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
        when(delegate.findSimilarIds(id("1"))).thenReturn(List.of(id("2"), id("3")));

        assertThat(catalog.findSimilarIds(id("1"))).containsExactly(id("2"), id("3"));
        assertThat(catalog.findSimilarIds(id("1"))).containsExactly(id("2"), id("3"));

        verify(delegate, times(1)).findSimilarIds(id("1"));
    }

    @Test
    void propagatesNotFoundWithoutCachingIt() {
        when(delegate.findSimilarIds(id("99"))).thenThrow(new ProductNotFoundException(id("99")));

        assertThatThrownBy(() -> catalog.findSimilarIds(id("99"))).isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> catalog.findSimilarIds(id("99"))).isInstanceOf(ProductNotFoundException.class);

        verify(delegate, times(2)).findSimilarIds(id("99"));
    }

    @Test
    void reportsUnavailableWhenSimilarIdsAreTooSlow() {
        when(delegate.findSimilarIds(id("1"))).thenAnswer(invocation -> {
            upstreamReleased.await();
            return List.of(id("2"));
        });

        assertThatThrownBy(() -> catalog.findSimilarIds(id("1")))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void slowProductIsSkippedButCachedWhenItArrives() {
        when(delegate.findById(id("1000"))).thenAnswer(invocation -> {
            upstreamReleased.await();
            return Optional.of(product("1000"));
        });

        // First request gives up after the lookup timeout...
        assertThatThrownBy(() -> catalog.findById(id("1000")))
                .isInstanceOf(ProductCatalogUnavailableException.class);

        // ...but the upstream call keeps running and fills the cache
        upstreamReleased.countDown();
        assertThat(catalog.findById(id("1000"))).contains(product("1000"));
        verify(delegate, times(1)).findById(id("1000"));
    }

    @Test
    void concurrentRequestsShareOneUpstreamCall() {
        CachingProductCatalog patientCatalog = newCatalog(Duration.ofSeconds(5));
        when(delegate.findById(id("1"))).thenAnswer(invocation -> {
            upstreamReleased.await();
            return Optional.of(product("1"));
        });

        List<CompletableFuture<Optional<Product>>> requests = IntStream.range(0, 50)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> patientCatalog.findById(id("1")), executor))
                .toList();

        // While the upstream call is still in flight, the waiting requests have not started any other
        verify(delegate, after(100).times(1)).findById(id("1"));
        upstreamReleased.countDown();
        assertThat(requests).allSatisfy(request -> assertThat(request.join()).contains(product("1")));
        verify(delegate, times(1)).findById(id("1"));
    }

    private CachingProductCatalog newCatalog(Duration lookupTimeout) {
        return new CachingProductCatalog(delegate, lookupTimeout, TTL, 100, executor, nanoTime::get);
    }
}
