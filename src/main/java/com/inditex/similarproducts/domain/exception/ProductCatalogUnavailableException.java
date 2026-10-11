package com.inditex.similarproducts.domain.exception;

import com.inditex.similarproducts.domain.model.ProductId;

/**
 * The product catalog could not answer: an upstream error, a timeout, invalid data, or a protection
 * mechanism (circuit breaker, bulkhead) refusing the call. The message ends with the root cause.
 */
public class ProductCatalogUnavailableException extends RuntimeException {

    public ProductCatalogUnavailableException(ProductId productId, Throwable cause) {
        super("Product catalog unavailable while looking up product " + productId + ": " + describe(rootCause(cause)),
                cause);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
