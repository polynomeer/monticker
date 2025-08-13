package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.BackoffStrategy;
import com.polynomeer.domain.price.repository.CachePriceRepository;
import com.polynomeer.domain.price.repository.FixedBackoff;
import com.polynomeer.domain.price.repository.TimeSeriesPriceRepository;
import com.polynomeer.shared.common.error.PriceErrorCode;
import com.polynomeer.shared.common.error.PriceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceQueryServiceImpl implements PriceQueryService {

    private final CachePriceRepository redisRepository;
    private final TimeSeriesPriceRepository timeSeriesRepository;
    private final Executor executor;
    private final BackoffStrategy backoff;
    private final int retries;

    private final ConcurrentHashMap<String, CompletableFuture<Price>> flights;

    @Autowired
    public PriceQueryServiceImpl(CachePriceRepository redisRepository,
                                 TimeSeriesPriceRepository timeSeriesRepository) {
        this(redisRepository, timeSeriesRepository,
                ForkJoinPool.commonPool(),
                new FixedBackoff(Duration.ofMillis(30)),
                2,
                new ConcurrentHashMap<>());
    }

    @Override
    public Price getCurrentPrice(String tickerCode) {
        return flights
                .computeIfAbsent(tickerCode, k ->
                        CompletableFuture.supplyAsync(() -> loadOnce(k), executor)
                )
                .whenComplete((r, t) -> flights.remove(tickerCode))
                .join();
    }

    Price loadOnce(String code) {
        var cached = redisRepository.find(code);
        if (cached.isPresent()) {
            log.debug("Cache hit for {}", code);
            return cached.get();
        }
        log.debug("Cache miss for {}", code);

        for (int i = 0; i < retries; i++) {
            backoff.pause();
            var again = redisRepository.find(code);
            if (again.isPresent()) {
                log.debug("Cache filled by peer for {}", code);
                return again.get();
            }
        }

        var latest = timeSeriesRepository.findLatest(code)
                .orElseThrow(() -> new PriceNotFoundException(PriceErrorCode.PRICE_NOT_FOUND));

        var existing = redisRepository.find(code);
        if (existing.isPresent() && existing.get().equals(latest)) {
            log.debug("Skip redis write: identical value for {}", code);
            return latest;
        }

        boolean wrote = redisRepository.saveIfAbsent(code, latest);
        log.debug("Redis write {}", wrote ? "SET(NX)" : "SKIPPED");
        return latest;
    }
}
