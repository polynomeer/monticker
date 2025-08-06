package com.polynomeer.infra.timescaledb;

import com.polynomeer.domain.price.model.Price;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeSeriesPriceRepositoryImplTest {

    private TimeSeriesPriceRepositoryImpl timeSeriesRepo;

    @BeforeEach
    void setUp() {
        timeSeriesRepo = new TimeSeriesPriceRepositoryImpl();
    }

    @Test
    @DisplayName("등록된 티커에 대해 가격 정보를 반환한다")
    void shouldReturnPriceIfTickerExists() {
        Optional<Price> result = timeSeriesRepo.findLatest("005930");

        assertTrue(result.isPresent());
        assertEquals("005930", result.get().tickerCode());
    }

    @Test
    @DisplayName("존재하지 않는 티커에 대해 Optional.empty를 반환한다")
    void shouldReturnEmptyIfTickerNotExists() {
        Optional<Price> result = timeSeriesRepo.findLatest("INVALID");

        assertTrue(result.isEmpty());
    }
}
