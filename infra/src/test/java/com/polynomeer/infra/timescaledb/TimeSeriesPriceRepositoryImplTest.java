package com.polynomeer.infra.timescaledb;

import com.polynomeer.domain.price.model.Price;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeSeriesPriceRepositoryImplTest {

    private TimeSeriesPriceRepositoryImpl timeSeriesRepo;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        timeSeriesRepo = new TimeSeriesPriceRepositoryImpl(jdbcTemplate);

        jdbcTemplate.execute("DROP TABLE IF EXISTS price_history");
        jdbcTemplate.execute("""
                    CREATE TABLE price_history (
                        ticker_code VARCHAR(20),
                        timestamp TIMESTAMP,
                        price BIGINT,
                        volume BIGINT
                    )
                """);

        // 해외 주식 예시 데이터 삽입 (AAPL)
        jdbcTemplate.execute("""
                    INSERT INTO price_history (ticker_code, timestamp, price, volume) VALUES
                    ('AAPL', '2024-08-01T09:00:00', 19500, 500000)
                """);
    }

    @Test
    @DisplayName("해외 종목 AAPL의 최근 시세를 반환한다")
    void shouldReturnLatestPriceForAAPL() {
        Optional<Price> result = timeSeriesRepo.findLatest("AAPL");

        assertTrue(result.isPresent());
        assertEquals("AAPL", result.get().tickerCode());
        assertEquals(19500, result.get().price());
    }

    @Test
    @DisplayName("존재하지 않는 해외 종목에 대해 Optional.empty 반환")
    void shouldReturnEmptyForInvalidTicker() {
        Optional<Price> result = timeSeriesRepo.findLatest("TSLA");
        assertTrue(result.isEmpty());
    }
}

