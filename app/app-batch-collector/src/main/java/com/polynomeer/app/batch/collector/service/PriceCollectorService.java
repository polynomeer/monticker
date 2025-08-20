package com.polynomeer.app.batch.collector.service;

import com.polynomeer.infra.external.PriceSnapshot;
import com.polynomeer.infra.external.YahooFinancePriceClient;
import com.polynomeer.infra.external.YahooResponseParser;
import com.polynomeer.infra.redis.RedisPriceStore;
import com.polynomeer.infra.timescaledb.TimescalePriceStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceCollectorService {

    private final YahooFinancePriceClient yahooFinanceClient;
    private final YahooResponseParser parser;
    private final RedisPriceStore redisStore;
    private final TimescalePriceStore timescaleStore;

    public void collectPrices(List<String> tickers) {
        if (tickers.isEmpty()) return;

        try {
            String rawResponse = yahooFinanceClient.fetchQuotes(tickers);
            List<PriceSnapshot> snapshots = parser.parse(rawResponse);

            for (PriceSnapshot snapshot : snapshots) {
                redisStore.save(snapshot);
                timescaleStore.save(snapshot);
                log.debug("Saved price: {}", snapshot);
            }

            log.info("✅ {} tickers collected and saved.", snapshots.size());

        } catch (Exception e) {
            log.error("Failed to collect prices for tickers: {}", tickers, e);
            // retry/backoff는 RetryExecutor 또는 AOP 등에서 처리 예정
        }
    }
}
