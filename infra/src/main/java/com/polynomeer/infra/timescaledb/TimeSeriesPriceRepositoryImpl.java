package com.polynomeer.infra.timescaledb;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.TimeSeriesPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.ZoneId;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TimeSeriesPriceRepositoryImpl implements TimeSeriesPriceRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<Price> findLatest(String tickerCode) {
        String sql = """
                    SELECT price, volume, timestamp
                    FROM price_history
                    WHERE ticker_code = ?
                    ORDER BY timestamp DESC
                    LIMIT 1
                """;

        log.debug("[TimescaleDB] Executing SQL to find latest price for tickerCode={}", tickerCode);

        try {
            Price price = jdbcTemplate.queryForObject(sql, new Object[]{tickerCode}, (rs, rowNum) -> {
                Price p = new Price(
                        tickerCode,
                        rs.getLong("price"),
                        0L, // change (not available)
                        0.0, // changeRate (not available)
                        rs.getLong("volume"),
                        rs.getTimestamp("timestamp").toInstant().atZone(ZoneId.of("Asia/Seoul"))
                );
                log.debug("[TimescaleDB] Query result mapped: {}", p);
                return p;
            });

            return Optional.ofNullable(price);
        } catch (Exception e) {
            log.warn("[TimescaleDB] Failed to fetch latest price for {}: {}", tickerCode, e.getMessage());
            return Optional.empty();
        }
    }
}
