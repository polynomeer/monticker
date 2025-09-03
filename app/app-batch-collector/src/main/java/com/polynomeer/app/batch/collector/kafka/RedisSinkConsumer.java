package com.polynomeer.app.batch.collector.kafka;

import com.polynomeer.infra.external.PriceSnapshot;
import com.polynomeer.infra.redis.RedisPriceStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSinkConsumer {

    private final RedisPriceStore redisStore;

    @KafkaListener(topics = "${kafka.topic.quote-normalized}", groupId = "redis-sink")
    public void onSnapshot(PriceSnapshot s) {
        redisStore.save(s);
    }
}

