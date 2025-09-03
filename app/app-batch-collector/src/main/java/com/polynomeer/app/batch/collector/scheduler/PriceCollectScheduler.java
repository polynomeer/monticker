package com.polynomeer.app.batch.collector.scheduler;

import com.polynomeer.app.batch.collector.kafka.FetchCommandProducer;
import com.polynomeer.app.batch.collector.service.FetchIntervalService;
import com.polynomeer.app.batch.collector.service.PopularTickerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceCollectScheduler {

    private final PopularTickerService popularTickerService;
    private final FetchIntervalService fetchIntervalService;
    private final FetchCommandProducer commandProducer;

    @Scheduled(fixedRate = 1000)
    public void collect() {
        try {
            var popular = popularTickerService.getPopularTickers();
            var targets = fetchIntervalService.filterTickersToFetch(popular);
            for (String t : targets) commandProducer.send(t);
            if (!targets.isEmpty()) log.info("Enqueued {} tickers", targets.size());
        } catch (Exception e) {
            log.error("Scheduler error", e);
        }
    }
}

