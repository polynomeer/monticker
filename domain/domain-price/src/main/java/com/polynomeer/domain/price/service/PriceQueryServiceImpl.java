package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.CachePriceRepository;
import com.polynomeer.domain.price.repository.TimeSeriesPriceRepository;
import com.polynomeer.shared.common.error.PriceErrorCode;
import com.polynomeer.shared.common.error.PriceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceQueryServiceImpl implements PriceQueryService {

    private final CachePriceRepository redisRepository;
    private final TimeSeriesPriceRepository timeSeriesRepository;

    @Override
    public Price getCurrentPrice(String tickerCode) {
        return redisRepository.find(tickerCode)
                .map(cached -> {
                    log.debug("Cache hit for {}", tickerCode);
                    return cached;
                })
                .or(() -> {
                    log.debug("Cache miss for {}", tickerCode);
                    return timeSeriesRepository.findLatest(tickerCode)
                            .map(latest -> {
                                log.debug("DB fallback for {}", tickerCode);
                                redisRepository.save(tickerCode, latest);
                                return latest;
                            });
                })
                .orElseThrow(() -> {
                    log.warn("Price not found in cache or DB for {}", tickerCode);
                    return new PriceNotFoundException(PriceErrorCode.PRICE_NOT_FOUND);
                });
    }
}
