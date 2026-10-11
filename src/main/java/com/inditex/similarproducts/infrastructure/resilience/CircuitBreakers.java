package com.inditex.similarproducts.infrastructure.resilience;

import com.inditex.similarproducts.application.port.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.model.ProductId;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Helpers shared by the circuit breaking decorators.
 */
final class CircuitBreakers {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakers.class);

    private CircuitBreakers() {
    }

    /**
     * Runs a lookup through a breaker; a call refused by an open breaker is reported as unavailable.
     */
    static <T> T call(CircuitBreaker breaker, ProductId productId, Supplier<T> lookup) {
        try {
            return breaker.executeSupplier(lookup);
        } catch (CallNotPermittedException e) {
            throw new ProductCatalogUnavailableException(productId, e);
        }
    }

    /**
     * Logs the breaker's state changes: they are the useful signal, rather than every failed call.
     */
    static CircuitBreaker withStateLogging(CircuitBreaker breaker) {
        breaker.getEventPublisher().onStateTransition(event ->
                log.info("Circuit breaker '{}': {}", breaker.getName(), event.getStateTransition()));
        return breaker;
    }
}
