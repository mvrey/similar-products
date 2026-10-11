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
     */
    @Bean(name = PRODUCT_LOOKUP_EXECUTOR, destroyMethod = "close")
    ExecutorService productLookupExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
