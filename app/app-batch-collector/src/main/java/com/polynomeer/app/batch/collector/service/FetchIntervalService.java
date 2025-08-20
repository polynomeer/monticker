package com.polynomeer.app.batch.collector.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class FetchIntervalService {

    // 종목별 수집 주기 (ex. AAPL: 1초, TSLA: 5초)
    private final Map<String, Duration> intervalMap = new HashMap<>();

    // 종목별 마지막 수집 시점
    private final Map<String, Instant> lastFetchedMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Stub: 종목별 수집 주기 설정
        intervalMap.put("AAPL", Duration.ofSeconds(1));
        intervalMap.put("TSLA", Duration.ofSeconds(5));
        intervalMap.put("NVDA", Duration.ofSeconds(2));
        intervalMap.put("AMZN", Duration.ofSeconds(10));
        intervalMap.put("MSFT", Duration.ofSeconds(3));
    }

    /**
     * 현재 시점에 수집 가능한 종목만 필터링
     */
    public List<String> filterTickersToFetch(List<String> inputTickers) {
        Instant now = Instant.now();

        return inputTickers.stream()
                .filter(ticker -> {
                    Duration interval = intervalMap.getOrDefault(ticker, Duration.ofSeconds(10)); // 기본 10초
                    Instant lastFetched = lastFetchedMap.getOrDefault(ticker, Instant.EPOCH);

                    boolean shouldFetch = Duration.between(lastFetched, now).compareTo(interval) >= 0;

                    if (shouldFetch) {
                        lastFetchedMap.put(ticker, now);
                    }

                    return shouldFetch;
                })
                .collect(Collectors.toList());
    }
}
