package com.inditex.similarproducts.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
class ConcurrencyConfig {

    /**
     * One virtual thread per product lookup: lookups are I/O bound, so they are cheap to run in parallel.
     */
    @Bean(destroyMethod = "close")
    ExecutorService productLookupExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
