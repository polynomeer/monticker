package com.polynomeer.domain.price.repository;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class PriceCacheConfig {

    @Bean
    public Executor priceQueryExecutor() {
        return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    @Bean
    @Profile("!test")
    public BackoffStrategy fixedBackoff() {
        return new FixedBackoff(Duration.ofMillis(30));
    }

    @Bean
    @Profile("test")
    public BackoffStrategy noOpBackoff() {
        return () -> {
        };
    }
}
