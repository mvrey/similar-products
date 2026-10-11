package com.inditex.similarproducts.infrastructure.resilience;

import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.application.port.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.Product;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static com.inditex.similarproducts.support.TestProducts.id;
import static com.inditex.similarproducts.support.TestProducts.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BulkheadProductCatalogTest {

    // One call at a time; a second one is rejected straight away
    private static final BulkheadSettings SETTINGS = new BulkheadSettings(1, Duration.ZERO);

    private final ProductCatalog delegate = mock(ProductCatalog.class);
    private final BulkheadProductCatalog catalog = new BulkheadProductCatalog(delegate, SETTINGS);

    @Test
    void rejectsCallsBeyondTheConcurrencyLimitUntilASlotIsFree() throws InterruptedException {
        CountDownLatch slowCallStarted = new CountDownLatch(1);
        CountDownLatch slowCallReleased = new CountDownLatch(1);
        when(delegate.findById(id("1"))).thenAnswer(invocation -> {
            slowCallStarted.countDown();
            slowCallReleased.await();
            return Optional.of(product("1"));
        });
        when(delegate.findById(id("2"))).thenReturn(Optional.of(product("2")));

        CompletableFuture<Optional<Product>> slowCall = CompletableFuture.supplyAsync(() -> catalog.findById(id("1")));
        slowCallStarted.await();

        assertThatThrownBy(() -> catalog.findById(id("2")))
                .isInstanceOf(ProductCatalogUnavailableException.class)
                .hasCauseInstanceOf(BulkheadFullException.class);

        slowCallReleased.countDown();
        assertThat(slowCall.join()).contains(product("1"));
        assertThat(catalog.findById(id("2"))).contains(product("2"));
    }

    @Test
    void publishesItsFreeSlotsAsAMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        catalog.bindTo(registry);

        assertThat(registry.get("resilience4j.bulkhead.available.concurrent.calls").gauge().value()).isEqualTo(1);
    }
}
