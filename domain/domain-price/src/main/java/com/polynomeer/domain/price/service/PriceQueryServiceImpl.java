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
        Price cached;
        try {
            cached = redisRepository.find(tickerCode);
            if (cached != null) {
                log.debug("Cache hit for {}", tickerCode);
                return cached;
            }
            log.debug("Cache miss for {}", tickerCode);
        } catch (Exception e) {
            log.warn("Redis access failed for {}: {}", tickerCode, e.getMessage());
        }

        Price latestFromDb;
        try {
            latestFromDb = timeSeriesRepository.findLatest(tickerCode);
        } catch (Exception e) {
            log.error("DB access failed for {}: {}", tickerCode, e.getMessage(), e);
            throw new PriceNotFoundException(PriceErrorCode.PRICE_NOT_FOUND);
        }

        if (latestFromDb != null) {
            log.debug("DB fallback for {}", tickerCode);
            try {
                redisRepository.save(tickerCode, latestFromDb);
            } catch (Exception e) {
                log.warn("Redis write failed for {}: {}", tickerCode, e.getMessage());
            }

            return latestFromDb;
        }

        log.warn("Price not found in cache or DB for {}", tickerCode);
        throw new PriceNotFoundException(PriceErrorCode.PRICE_NOT_FOUND);
    }
}
