package com.polynomeer.app.batch.collector.service;

import com.polynomeer.infra.external.PriceDataProvider;
import com.polynomeer.infra.external.PriceSnapshot;
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

    private final PriceDataProvider dataProvider;
    private final RedisPriceStore redisStore;
    private final TimescalePriceStore timescaleStore;

    public void collectPrices(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return;

        try {
            List<PriceSnapshot> snapshots = dataProvider.fetchSnapshots(tickers);
            for (PriceSnapshot s : snapshots) {
                redisStore.save(s);
                timescaleStore.save(s);
                log.debug("Saved price: {}", s);
            }
            log.info("✅ {} tickers collected and saved.", snapshots.size());
        } catch (Exception e) {
            log.error("Failed to collect prices for tickers: {}", tickers, e);
        }
    }
}
