package com.polynomeer.app.batch.collector.scheduler;

import com.polynomeer.app.batch.collector.service.FetchIntervalService;
import com.polynomeer.app.batch.collector.service.PopularTickerService;
import com.polynomeer.app.batch.collector.service.PriceCollectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceCollectScheduler {

    private final PopularTickerService popularTickerService;
    private final FetchIntervalService fetchIntervalService;
    private final PriceCollectorService priceCollectorService;

    @Scheduled(fixedRate = 1000)
    public void collect() {
        try {
            List<String> popularTickers = popularTickerService.getPopularTickers();
            List<String> tickersToFetch = fetchIntervalService.filterTickersToFetch(popularTickers);

            if (tickersToFetch.isEmpty()) {
                log.debug("No tickers to fetch at this moment.");
                return;
            }

            log.info("Fetching prices for tickers: {}", tickersToFetch);
            priceCollectorService.collectPrices(tickersToFetch);
        } catch (Exception e) {
            log.error("Error occurred during scheduled price collection", e);
        }
    }
}
