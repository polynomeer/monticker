package com.polynomeer.infra.redis;

import com.polynomeer.infra.external.PriceSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPriceStore {

    private final StringRedisTemplate redisTemplate;

    private static final Duration TTL = Duration.ofSeconds(60);

    public void save(PriceSnapshot snapshot) {
        String key = "price:" + snapshot.ticker();
        Map<String, String> data = Map.of(
                "price", String.valueOf(snapshot.price()),
                "volume", String.valueOf(snapshot.volume()),
                "currency", snapshot.currency(),
                "timestamp", snapshot.timestamp().toString()
        );

        try {
            redisTemplate.opsForHash().putAll(key, data);
            redisTemplate.expire(key, TTL);
        } catch (Exception e) {
            log.error("Redis 저장 실패: {}", key, e);
        }
    }
}
