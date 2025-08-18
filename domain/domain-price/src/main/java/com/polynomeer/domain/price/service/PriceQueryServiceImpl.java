package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.BackoffStrategy;
import com.polynomeer.domain.price.repository.CachePriceRepository;
import com.polynomeer.domain.price.repository.PriceCacheProperties;
import com.polynomeer.domain.price.repository.TimeSeriesPriceRepository;
import com.polynomeer.shared.common.error.PriceErrorCode;
import com.polynomeer.shared.common.error.PriceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.Executor;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceQueryServiceImpl implements PriceQueryService {

    private final CachePriceRepository redisRepository;
    private final TimeSeriesPriceRepository timeSeriesRepository;
    private final SingleFlightExecutor<String, Price> singleFlight;
    @Qualifier("priceQueryExecutor")
    private final Executor executor;
    private final BackoffStrategy backoff;
    private final PriceCacheProperties props;

    @Override
    public Price getCurrentPrice(String tickerCode) {
        return singleFlight.execute(tickerCode, () -> loadOnce(tickerCode), executor);
    }

    private Price loadOnce(String code) {
        return redisRepository.find(code).or(() -> {
            log.debug("Cache miss for {}", code);
            for (int i = 0; i < props.getRetries(); i++) {
                backoff.pause();
                var again = redisRepository.find(code);
                if (again.isPresent()) {
                    log.debug("Cache filled by peer for {}", code);
                    return again;
                }
            }
            return Optional.empty();
        }).or(() -> {
            var latest = timeSeriesRepository.findLatest(code)
                    .orElseThrow(() -> new PriceNotFoundException(PriceErrorCode.PRICE_NOT_FOUND));

            var existing = redisRepository.find(code);
            if (existing.isPresent() && existing.get().equals(latest)) {
                log.debug("Skip redis write: identical value for {}", code);
                return Optional.of(latest);
            }

            boolean wrote = redisRepository.saveIfAbsent(code, latest);
            log.debug("Redis write {}", wrote ? "SET(NX)" : "SKIPPED");
            return Optional.of(latest);
        }).orElseThrow(() -> new PriceNotFoundException(PriceErrorCode.PRICE_NOT_FOUND));
    }
}
