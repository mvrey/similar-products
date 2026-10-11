package com.inditex.similarproducts.application;

import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.exception.ProductNotFoundException;
import com.inditex.similarproducts.domain.model.Product;
import com.inditex.similarproducts.domain.model.ProductId;
import com.inditex.similarproducts.domain.port.ProductCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.inditex.similarproducts.support.TestProducts.id;
import static com.inditex.similarproducts.support.TestProducts.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetSimilarProductsUseCaseTest {

    private final ProductCatalog catalog = mock(ProductCatalog.class);
    private ExecutorService executor;
    private GetSimilarProductsUseCase useCase;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        useCase = new GetSimilarProductsUseCase(catalog, executor);
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void returnsSimilarProductsInSimilarityOrder() {
        when(catalog.findSimilarIds(id("1"))).thenReturn(Optional.of(List.of(id("2"), id("3"), id("4"))));
        when(catalog.findById(id("2"))).thenReturn(Optional.of(product("2")));
        when(catalog.findById(id("3"))).thenReturn(Optional.of(product("3")));
        when(catalog.findById(id("4"))).thenReturn(Optional.of(product("4")));

        assertThat(useCase.execute(id("1")))
                .extracting(Product::id)
                .containsExactly(id("2"), id("3"), id("4"));
    }

    @Test
    void returnsEachSimilarProductOnlyOnce() {
        when(catalog.findSimilarIds(id("1"))).thenReturn(Optional.of(List.of(id("2"), id("3"), id("2"))));
        when(catalog.findById(id("2"))).thenReturn(Optional.of(product("2")));
        when(catalog.findById(id("3"))).thenReturn(Optional.of(product("3")));

        assertThat(useCase.execute(id("1")))
                .extracting(Product::id)
                .containsExactly(id("2"), id("3"));
        verify(catalog, times(1)).findById(id("2"));
    }

    @Test
    void skipsSimilarProductsThatDoNotExist() {
        when(catalog.findSimilarIds(id("4"))).thenReturn(Optional.of(List.of(id("1"), id("2"), id("5"))));
        when(catalog.findById(id("1"))).thenReturn(Optional.of(product("1")));
        when(catalog.findById(id("2"))).thenReturn(Optional.of(product("2")));
        when(catalog.findById(id("5"))).thenReturn(Optional.empty());

        assertThat(useCase.execute(id("4")))
                .extracting(Product::id)
                .containsExactly(id("1"), id("2"));
    }

    @Test
    void skipsSimilarProductsThatCannotBeRetrieved() {
        when(catalog.findSimilarIds(id("5"))).thenReturn(Optional.of(List.of(id("1"), id("2"), id("6"))));
        when(catalog.findById(id("1"))).thenReturn(Optional.of(product("1")));
        when(catalog.findById(id("2"))).thenReturn(Optional.of(product("2")));
        when(catalog.findById(id("6")))
                .thenThrow(new ProductCatalogUnavailableException(id("6"), new RuntimeException("500")));

        assertThat(useCase.execute(id("5")))
                .extracting(Product::id)
                .containsExactly(id("1"), id("2"));
    }

    @Test
    void returnsEmptyListWhenThereAreNoSimilarProducts() {
        when(catalog.findSimilarIds(id("1"))).thenReturn(Optional.of(List.of()));

        assertThat(useCase.execute(id("1"))).isEmpty();
        verify(catalog, never()).findById(any());
    }

    @Test
    void throwsNotFoundWhenProductDoesNotExist() {
        when(catalog.findSimilarIds(id("99"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(id("99")))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void fetchesProductDetailsInParallel() {
        // Each lookup waits until all three are running at the same time; one at a time, the barrier would time out
        CyclicBarrier allLookupsRunning = new CyclicBarrier(3);
        when(catalog.findSimilarIds(id("1"))).thenReturn(Optional.of(List.of(id("2"), id("3"), id("4"))));
        when(catalog.findById(any())).thenAnswer(invocation -> {
            allLookupsRunning.await(5, TimeUnit.SECONDS);
            return Optional.of(product(invocation.<ProductId>getArgument(0).value()));
        });

        assertThat(useCase.execute(id("1"))).hasSize(3);
    }
}
