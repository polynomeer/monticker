package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.BackoffStrategy;
import com.polynomeer.domain.price.repository.CachePriceRepository;
import com.polynomeer.domain.price.repository.TimeSeriesPriceRepository;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class FakeRepositories {

    static class FakeTimeSeriesRepository implements TimeSeriesPriceRepository {
        final AtomicInteger reads = new AtomicInteger();
        final Price fixed;
        final long delayMs;

        FakeTimeSeriesRepository(String code, double value, long delayMs) {
            this.fixed = new Price(code, (long) (value * 100), 0, 0.0, 0, ZonedDateTime.now());
            this.delayMs = delayMs;
        }

        @Override
        public Optional<Price> findLatest(String tickerCode) {
            reads.incrementAndGet();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Optional.of(fixed);
        }
    }

    static class NoopBackoff implements BackoffStrategy {
        @Override
        public void pause() {
        }
    }


    static class FakeRedisRepository implements CachePriceRepository {
        private final ConcurrentHashMap<String, Price> map = new ConcurrentHashMap<>();
        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public Optional<Price> find(String tickerCode) {
            return Optional.ofNullable(map.get(tickerCode));
        }

        @Override
        public void save(String tickerCode, Price price) {
            map.put(tickerCode, price);
            writes.incrementAndGet();
        }

        @Override
        public boolean saveIfAbsent(String tickerCode, Price price) {
            Price prev = map.putIfAbsent(tickerCode, price);
            if (prev == null) {
                writes.incrementAndGet();
                return true;
            }
            return false;
        }

        int writeCount() {
            return writes.get();
        }
    }

}
