package com.polynomeer.domain.price.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Component
public class SingleFlightExecutor<K, V> {

    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    public V execute(K key, Supplier<V> supplier, Executor executor) {
        CompletableFuture<V> future = inFlight.computeIfAbsent(
                key, k -> CompletableFuture.supplyAsync(supplier, executor)
        );
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        } finally {
            inFlight.remove(key);
        }
    }
}
