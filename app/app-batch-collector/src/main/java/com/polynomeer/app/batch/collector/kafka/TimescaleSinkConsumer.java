package com.polynomeer.app.batch.collector.kafka;

import com.polynomeer.infra.external.PriceSnapshot;
import com.polynomeer.infra.timescaledb.TimescalePriceStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TimescaleSinkConsumer {

    private final TimescalePriceStore timescaleStore;

    @KafkaListener(topics = "${kafka.topic.quote-normalized}", groupId = "tsdb-sink")
    public void onSnapshot(PriceSnapshot s) {
        timescaleStore.save(s);
    }
}
