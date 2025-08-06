package com.polynomeer.infra.timescaledb;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.TimeSeriesPriceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Repository
public class TimeSeriesPriceRepositoryImpl implements TimeSeriesPriceRepository {

    private final Map<String, Price> mockDb = new HashMap<>();

    public TimeSeriesPriceRepositoryImpl() {
        mockDb.put("005930", new Price("005930", 76800, -300, -0.39, 22000000, ZonedDateTime.now()));
        mockDb.put("AAPL", new Price("AAPL", 19500, 200, 1.03, 15000000, ZonedDateTime.now()));
    }

    @Override
    public Price findLatest(String tickerCode) {
        try {
            log.debug("[TimescaleDB] SELECT * FROM price_history WHERE ticker_code = '{}'", tickerCode);

            Price price = mockDb.get(tickerCode);
            if (price == null) {
                log.warn("[TimescaleDB] No data found for ticker: {}", tickerCode);
            }

            return price;
        } catch (Exception e) {
            log.error("[TimescaleDB] Failed to read price for {}: {}", tickerCode, e.getMessage(), e);
            return null;
        }
    }
}

