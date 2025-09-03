package com.polynomeer.app.batch.collector.kafka;

import com.polynomeer.infra.external.PriceDataProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class FetchWorker {

    private final PriceDataProvider dataProvider;
    private final NormalizedQuoteProducer out;

    @KafkaListener(topics = "${kafka.topic.quote-request}", groupId = "fetcher")
    public void onFetch(FetchCommand cmd) {
        try {
            var snaps = dataProvider.fetchSnapshots(List.of(cmd.ticker()));
            for (var s : snaps) out.publish(s);
        } catch (Exception e) {
            // 임시: 간단 로깅만. 나중에 @RetryableTopic/DLT 적용
            log.warn("Fetch failed for {}: {}", cmd.ticker(), e.getMessage());
        }
    }
}
