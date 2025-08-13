package com.polynomeer.domain.price.repository;

import java.time.Duration;

public class FixedBackoff implements BackoffStrategy {
    private final Duration d;

    public FixedBackoff(Duration d) {
        this.d = d;
    }

    @Override
    public void pause() {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}