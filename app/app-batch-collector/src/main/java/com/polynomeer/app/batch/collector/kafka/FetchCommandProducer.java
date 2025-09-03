package com.polynomeer.app.batch.collector.kafka;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FetchCommandProducer {
    private final KafkaTemplate<String, FetchCommand> kafka;
    @Value("${kafka.topic.quote-request}")
    String topic;

    public void send(String ticker) {
        var cmd = new FetchCommand(ticker, System.currentTimeMillis(), UUID.randomUUID().toString());
        kafka.send(topic, ticker, cmd);
    }
}
