package com.inditex.similarproducts.infrastructure.resilience;

import com.inditex.similarproducts.application.port.ProductCatalog;
import com.inditex.similarproducts.application.port.ProductCatalogUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static com.inditex.similarproducts.support.TestProducts.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimilarIdsCircuitBreakingCatalogTest {

    // Opens after 3 calls if at least half of them failed; stays open for a minute (longer than any test)
    private static final CircuitBreakerSettings SETTINGS =
            new CircuitBreakerSettings(50, 4, 3, Duration.ofMinutes(1), Duration.ofMinutes(30), 100);

    private final ProductCatalog delegate = mock(ProductCatalog.class);
    private final SimilarIdsCircuitBreakingCatalog catalog = new SimilarIdsCircuitBreakingCatalog(delegate, SETTINGS);

    @Test
    void failsFastWhenSimilarIdsServiceIsDown() {
        when(delegate.findSimilarIds(id("1"))).thenThrow(unavailable("1"));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> catalog.findSimilarIds(id("1")))
                    .isInstanceOf(ProductCatalogUnavailableException.class);
        }

        assertThatThrownBy(() -> catalog.findSimilarIds(id("1")))
                .hasCauseInstanceOf(CallNotPermittedException.class);
        verify(delegate, times(3)).findSimilarIds(id("1"));
    }

    @Test
    void notFoundDoesNotCountAsFailure() {
        when(delegate.findSimilarIds(id("99"))).thenReturn(Optional.empty());

        for (int i = 0; i < 5; i++) {
            assertThat(catalog.findSimilarIds(id("99"))).isEmpty();
        }

        verify(delegate, times(5)).findSimilarIds(id("99"));
    }

    @Test
    void passesProductDetailsThroughWithoutABreaker() {
        when(delegate.findById(id("6"))).thenThrow(unavailable("6"));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> catalog.findById(id("6"))).isInstanceOf(ProductCatalogUnavailableException.class);
        }

        verify(delegate, times(5)).findById(id("6"));
    }

    @Test
    void publishesTheBreakerState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        catalog.registerMetrics(registry);

        assertThat(registry.get("resilience4j.circuitbreaker.state").tags("name", "similar-ids", "state", "closed")
                .gauge().value()).isEqualTo(1);
    }

    private static ProductCatalogUnavailableException unavailable(String id) {
        return new ProductCatalogUnavailableException(id(id), new RuntimeException("500 Internal Server Error"));
    }
}
