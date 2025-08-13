package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.CachePriceRepository;
import com.polynomeer.domain.price.repository.TimeSeriesPriceRepository;
import com.polynomeer.shared.common.error.PriceErrorCode;
import com.polynomeer.shared.common.error.PriceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceQueryServiceImpl implements PriceQueryService {

    private final CachePriceRepository redisRepository;
    private final TimeSeriesPriceRepository timeSeriesRepository;

    private final ConcurrentHashMap<String, CompletableFuture<Price>> flights = new ConcurrentHashMap<>();
    private static final Duration BACKOFF = Duration.ofMillis(30);
    private static final int RETRIES = 2;

    @Override
    public Price getCurrentPrice(String tickerCode) {
        return flights
                .computeIfAbsent(tickerCode, k -> CompletableFuture.supplyAsync(() -> loadOnce(k)))
                .whenComplete((r, t) -> flights.remove(tickerCode))
                .join();
    }

    private Price loadOnce(String tickerCode) {
        var cached = redisRepository.find(tickerCode);
        if (cached.isPresent()) {
            log.debug("Cache hit for {}", tickerCode);
            return cached.get();
        }
        log.debug("Cache miss for {}", tickerCode);

        for (int i = 0; i < RETRIES; i++) {
            sleepQuietly(BACKOFF);
            var again = redisRepository.find(tickerCode);
            if (again.isPresent()) {
                log.debug("Cache filled by peer for {}", tickerCode);
                return again.get();
            }
        }

        var latest = timeSeriesRepository.findLatest(tickerCode)
                .orElseThrow(() -> {
                    log.warn("Price not found in cache or DB for {}", tickerCode);
                    return new PriceNotFoundException(PriceErrorCode.PRICE_NOT_FOUND);
                });

        var existing = redisRepository.find(tickerCode);
        if (existing.isPresent() && existing.get().equals(latest)) {
            log.debug("Skip redis write: identical value for {}", tickerCode);
            return latest;
        }

        boolean wrote = redisRepository.saveIfAbsent(tickerCode, latest);
        log.debug("Redis write {}", wrote ? "SET(NX)" : "SKIPPED");
        return latest;
    }

    private void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
