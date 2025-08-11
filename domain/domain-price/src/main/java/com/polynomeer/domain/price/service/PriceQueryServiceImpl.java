package com.polynomeer.domain.price.service;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.CachePriceRepository;
import com.polynomeer.domain.price.repository.ExternalPriceClient;
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
    private final ExternalPriceClient externalClient;

    @Override
    public Price getCurrentPrice(String tickerCode) {
        Price cached = redisRepository.find(tickerCode);
        if (cached != null) {
            log.debug("Cache hit for {}", tickerCode);
            return cached;
        }

        Price latestFromDb = timeSeriesRepository.findLatest(tickerCode);
        if (latestFromDb != null) {
            log.debug("DB fallback for {}", tickerCode);
            redisRepository.save(tickerCode, latestFromDb);
            return latestFromDb;
        }

        Price externalPrice = externalClient.fetchPrice(tickerCode);
        if (externalPrice != null) {
            log.debug("External API used for {}", tickerCode);
            redisRepository.save(tickerCode, externalPrice);
            return externalPrice;
        }

        throw new PriceNotFoundException(PriceErrorCode.PRICE_NOT_FOUND);
    }
}
