package com.polynomeer.infra.redis;

import com.polynomeer.domain.price.model.Price;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedisPriceRepositoryTest {

    private RedisTemplate redisTemplate;
    private RedisPriceRepository redisRepo;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        redisRepo = new RedisPriceRepository(redisTemplate);
    }

    @Test
    @DisplayName("정상적으로 캐시에 저장된다")
    void shouldSavePriceToCache() {
        var valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        Price price = dummyPrice();
        redisRepo.save("AAPL", price);

        verify(valueOps).set(eq("price::AAPL"), eq(price), any(Duration.class));
    }

    @Test
    @DisplayName("캐시에서 Price 조회 성공 시 Optional.of 반환")
    void shouldReturnOptionalOfPriceIfFound() {
        var valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("price::AAPL")).thenReturn(dummyPrice());

        Optional<Price> result = redisRepo.find("AAPL");

        assertTrue(result.isPresent());
        assertEquals("AAPL", result.get().tickerCode());
    }

    @Test
    @DisplayName("캐시에서 조회 실패 시 Optional.empty 반환")
    void shouldReturnEmptyIfCacheMiss() {
        var valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("price::AAPL")).thenReturn(null);

        Optional<Price> result = redisRepo.find("AAPL");

        assertTrue(result.isEmpty());
    }

    private Price dummyPrice() {
        return new Price("AAPL", 10000L, 100L, 1.0, 1000000L, ZonedDateTime.now());
    }
}
