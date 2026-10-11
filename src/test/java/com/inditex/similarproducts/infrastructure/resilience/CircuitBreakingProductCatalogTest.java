package com.inditex.similarproducts.infrastructure.resilience;

import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.port.ProductCatalog;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static com.inditex.similarproducts.support.TestProducts.id;
import static com.inditex.similarproducts.support.TestProducts.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CircuitBreakingProductCatalogTest {

    // Opens after 3 calls if at least half of them failed; stays open for a minute (longer than any test)
    private static final CircuitBreakerProperties PROPERTIES =
            new CircuitBreakerProperties(50, 4, 3, Duration.ofMinutes(1));

    private final ProductCatalog delegate = mock(ProductCatalog.class);
    private final CircuitBreakingProductCatalog catalog =
            new CircuitBreakingProductCatalog(delegate, PROPERTIES.toConfig());

    @Test
    void stopsCallingAProductThatKeepsFailing() {
        when(delegate.findById(id("6"))).thenThrow(unavailable("6"));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> catalog.findById(id("6"))).isInstanceOf(ProductCatalogUnavailableException.class);
        }

        // The breaker is now open: the call fails fast without reaching the delegate
        assertThatThrownBy(() -> catalog.findById(id("6")))
                .isInstanceOf(ProductCatalogUnavailableException.class)
                .hasCauseInstanceOf(CallNotPermittedException.class);
        verify(delegate, times(3)).findById(id("6"));
    }

    @Test
    void aFailingProductDoesNotAffectOtherProducts() {
        when(delegate.findById(id("6"))).thenThrow(unavailable("6"));
        when(delegate.findById(id("1"))).thenReturn(Optional.of(product("1")));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> catalog.findById(id("6"))).isInstanceOf(ProductCatalogUnavailableException.class);
        }

        assertThat(catalog.findById(id("1"))).contains(product("1"));
    }

    @Test
    void notFoundDoesNotCountAsFailure() {
        when(delegate.findById(id("5"))).thenReturn(Optional.empty());
        when(delegate.findSimilarIds(id("99"))).thenReturn(Optional.empty());

        for (int i = 0; i < 5; i++) {
            assertThat(catalog.findById(id("5"))).isEmpty();
            assertThat(catalog.findSimilarIds(id("99"))).isEmpty();
        }

        verify(delegate, times(5)).findById(id("5"));
        verify(delegate, times(5)).findSimilarIds(id("99"));
    }

    @Test
    void failsFastWhenSimilarIdsServiceIsDown() {
        when(delegate.findSimilarIds(id("1"))).thenThrow(unavailable("1"));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> catalog.findSimilarIds(id("1"))).isInstanceOf(ProductCatalogUnavailableException.class);
        }

        assertThatThrownBy(() -> catalog.findSimilarIds(id("1")))
                .hasCauseInstanceOf(CallNotPermittedException.class);
        verify(delegate, times(3)).findSimilarIds(id("1"));
    }

    @Test
    void recoversWhenTheProductWorksAgain() {
        CircuitBreakingProductCatalog catalog = new CircuitBreakingProductCatalog(delegate,
                new CircuitBreakerProperties(50, 4, 3, Duration.ofMillis(50)).toConfig());
        when(delegate.findById(id("6")))
                .thenThrow(unavailable("6"), unavailable("6"), unavailable("6"))
                .thenReturn(Optional.of(product("6")));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> catalog.findById(id("6"))).isInstanceOf(ProductCatalogUnavailableException.class);
        }

        // After the open period a test call goes through, succeeds, and the product is served again
        await().atMost(Duration.ofSeconds(2)).ignoreExceptions()
                .untilAsserted(() -> assertThat(catalog.findById(id("6"))).contains(product("6")));
        assertThat(catalog.findById(id("6"))).contains(product("6"));
    }

    private static ProductCatalogUnavailableException unavailable(String id) {
        return new ProductCatalogUnavailableException(id(id), new RuntimeException("500 Internal Server Error"));
    }
}
