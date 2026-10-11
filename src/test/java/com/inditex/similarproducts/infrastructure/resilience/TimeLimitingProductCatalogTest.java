package com.inditex.similarproducts.infrastructure.resilience;

import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.application.port.ProductCatalogTimeoutException;
import com.inditex.similarproducts.application.port.ProductCatalogUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.inditex.similarproducts.support.TestProducts.id;
import static com.inditex.similarproducts.support.TestProducts.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimeLimitingProductCatalogTest {

    private static final Duration TIMEOUT = Duration.ofMillis(100);

    private final ProductCatalog delegate = mock(ProductCatalog.class);
    // Slow lookups wait on this until the test lets them finish
    private final CountDownLatch upstreamReleased = new CountDownLatch(1);
    private ExecutorService executor;
    private TimeLimitingProductCatalog catalog;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        catalog = new TimeLimitingProductCatalog(delegate, TIMEOUT, executor);
    }

    @AfterEach
    void tearDown() {
        upstreamReleased.countDown();
        executor.close();
    }

    @Test
    void returnsResultsThatArriveInTime() {
        when(delegate.findById(id("1"))).thenReturn(Optional.of(product("1")));
        when(delegate.findSimilarIds(id("1"))).thenReturn(Optional.of(List.of(id("2"))));

        assertThat(catalog.findById(id("1"))).contains(product("1"));
        assertThat(catalog.findSimilarIds(id("1"))).contains(List.of(id("2")));
    }

    @Test
    void reportsUnavailableWhenTheLookupIsTooSlow() {
        when(delegate.findSimilarIds(id("1"))).thenAnswer(invocation -> {
            upstreamReleased.await();
            return Optional.of(List.of(id("2")));
        });

        assertThatThrownBy(() -> catalog.findSimilarIds(id("1")))
                .isInstanceOf(ProductCatalogTimeoutException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void letsASlowLookupFinishInTheBackground() {
        AtomicBoolean finished = new AtomicBoolean();
        when(delegate.findById(id("1000"))).thenAnswer(invocation -> {
            upstreamReleased.await();
            finished.set(true);
            return Optional.of(product("1000"));
        });

        assertThatThrownBy(() -> catalog.findById(id("1000")))
                .isInstanceOf(ProductCatalogUnavailableException.class);

        upstreamReleased.countDown();
        await().atMost(Duration.ofSeconds(2)).untilTrue(finished);
    }

    @Test
    void rethrowsTheDelegatesExceptionUnchanged() {
        ProductCatalogUnavailableException failure =
                new ProductCatalogUnavailableException(id("6"), new RuntimeException("500"));
        when(delegate.findById(id("6"))).thenThrow(failure);

        assertThatThrownBy(() -> catalog.findById(id("6"))).isSameAs(failure);
    }
}
