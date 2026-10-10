package com.inditex.similarproducts.infrastructure.cache;

import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.exception.ProductNotFoundException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import com.inditex.similarproducts.domain.port.ProductCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachingProductCatalogTest {

    private static final Duration LOOKUP_TIMEOUT = Duration.ofMillis(200);

    private final ProductCatalog delegate = mock(ProductCatalog.class);
    private ExecutorService executor;
    private CachingProductCatalog catalog;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        catalog = new CachingProductCatalog(delegate, LOOKUP_TIMEOUT, Duration.ofMinutes(10), 100, executor);
    }

    @AfterEach
    void tearDown() {
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
            Thread.sleep(1000);
            return List.of(id("2"));
        });

        assertThatThrownBy(() -> catalog.findSimilarIds(id("1")))
                .isInstanceOf(ProductCatalogUnavailableException.class);
    }

    @Test
    void slowProductIsSkippedButCachedWhenItArrives() throws InterruptedException {
        when(delegate.findById(id("1000"))).thenAnswer(invocation -> {
            Thread.sleep(500);
            return Optional.of(product("1000"));
        });

        // First request gives up after the lookup timeout...
        assertThatThrownBy(() -> catalog.findById(id("1000")))
                .isInstanceOf(ProductCatalogUnavailableException.class);

        // ...but the upstream call keeps running and fills the cache
        Thread.sleep(600);
        assertThat(catalog.findById(id("1000"))).contains(product("1000"));
        verify(delegate, times(1)).findById(id("1000"));
    }

    @Test
    void concurrentRequestsShareOneUpstreamCall() {
        when(delegate.findById(id("1"))).thenAnswer(invocation -> {
            Thread.sleep(100);
            return Optional.of(product("1"));
        });

        List<CompletableFuture<Optional<Product>>> requests = IntStream.range(0, 50)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> catalog.findById(id("1")), executor))
                .toList();

        assertThat(requests).allSatisfy(request -> assertThat(request.join()).contains(product("1")));
        verify(delegate, times(1)).findById(id("1"));
    }

    private static ProductId id(String value) {
        return new ProductId(value);
    }

    private static Product product(String id) {
        return new Product(id(id), "Product " + id, BigDecimal.TEN, true);
    }
}
