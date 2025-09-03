package com.polynomeer.app.batch.collector.kafka;

import com.polynomeer.infra.external.PriceSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NormalizedQuoteProducer {
    private final KafkaTemplate<String, PriceSnapshot> kafka;
    @Value("${kafka.topic.quote-normalized}")
    String topic;

    public void publish(PriceSnapshot snap) {
        kafka.send(topic, snap.ticker(), snap);
    }
}
