package com.polynomeer.infra.redis;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.CachePriceRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisPriceRepository implements CachePriceRepository {

    private static final Duration TTL = Duration.ofMinutes(1);
    private final RedisTemplate<String, Price> redisTemplate;

    private String key(String tickerCode) {
        return "price::" + tickerCode;
    }

    @PostConstruct
    public void saveTestData() {
        Price price = new Price(
                "BTC",
                50000L,
                100L,
                0.02,
                123456789L,
                ZonedDateTime.now()
        );
        redisTemplate.opsForValue().set("test:price:BTC", price);
    }

    @Override
    public Optional<Price> find(String tickerCode) {
        String redisKey = key(tickerCode);
        try {
            Price cached = redisTemplate.opsForValue().get(redisKey);
            if (cached != null) {
                log.debug("[Redis] Cache hit: {}", redisKey);
            } else {
                log.debug("[Redis] Cache miss: {}", redisKey);
            }
            return Optional.ofNullable(cached);
        } catch (Exception e) {
            log.warn("[Redis] Cache read error for {}: {}", redisKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String tickerCode, Price price) {
        String redisKey = key(tickerCode);
        try {
            redisTemplate.opsForValue().set(redisKey, price, TTL);
            log.debug("[Redis] Cache set: {} (TTL {}s)", redisKey, TTL.getSeconds());
        } catch (Exception e) {
            log.warn("[Redis] Cache write error for {}: {}", redisKey, e.getMessage());
        }
    }
}
