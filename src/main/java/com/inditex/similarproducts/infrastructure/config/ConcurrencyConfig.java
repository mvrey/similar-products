package com.inditex.similarproducts.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
class ConcurrencyConfig {

    /**
     * Name of the executor for product lookups. Injection points name it explicitly, so they never pick up
     * another Executor bean by accident.
     */
    static final String PRODUCT_LOOKUP_EXECUTOR = "productLookupExecutor";

    /**
     * One virtual thread per product lookup: lookups are I/O bound, so they are cheap to run in parallel.
     * <p>
     * On shutdown (after Spring has let in-flight requests finish), lookups still running only fill the cache
     * for requests that will never come, so they are interrupted rather than waited for.
     */
    @Bean(name = PRODUCT_LOOKUP_EXECUTOR, destroyMethod = "shutdownNow")
    ExecutorService productLookupExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
