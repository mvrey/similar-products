package com.inditex.similarproducts.infrastructure.rest;

import com.inditex.similarproducts.domain.exception.InvalidProductIdException;
import com.inditex.similarproducts.domain.exception.ProductCatalogUnavailableException;
import com.inditex.similarproducts.domain.exception.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Translates exceptions into HTTP responses using the RFC 9457 "problem details" format.
 * The base class does the same for Spring MVC's own errors (unknown path, unsupported method...),
 * so every error response has the same shape.
 */
@RestControllerAdvice
class RestExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail handleProductNotFound(ProductNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Product not found");
        return problem;
    }

    @ExceptionHandler(InvalidProductIdException.class)
    ProblemDetail handleInvalidProductId(InvalidProductIdException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid product id");
        return problem;
    }

    @ExceptionHandler(ProductCatalogUnavailableException.class)
    ProblemDetail handleCatalogUnavailable(ProductCatalogUnavailableException e) {
        // Expected while an upstream is down: one line, no stack trace (the message includes the root cause)
        log.warn(e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The product catalog is currently unavailable");
        problem.setTitle("Product catalog unavailable");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        // Not expected: a bug. Log the stack trace, but don't reveal internals to the client
        log.error("Unexpected error", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
        problem.setTitle("Internal server error");
        return problem;
    }
}
