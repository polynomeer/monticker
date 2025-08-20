package com.polynomeer.infra.timescaledb;

import com.polynomeer.infra.external.PriceSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

@Slf4j
@Component
@RequiredArgsConstructor
public class TimescalePriceStore {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String UPSERT_SQL = """
            INSERT INTO price_history (ticker_code, timestamp, price, volume, currency)
            VALUES (:ticker_code, :timestamp, :price, :volume, :currency)
            ON CONFLICT (ticker_code, timestamp)
            DO UPDATE SET price = EXCLUDED.price,
                          volume = EXCLUDED.volume,
                          currency = EXCLUDED.currency
            """;

    public void save(PriceSnapshot snapshot) {
        try {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("ticker_code", snapshot.ticker())
                    .addValue("timestamp", Timestamp.from(snapshot.timestamp()))
                    .addValue("price", snapshot.price())
                    .addValue("volume", snapshot.volume())
                    .addValue("currency", snapshot.currency());

            jdbc.update(UPSERT_SQL, params);
        } catch (Exception e) {
            log.error("TimescaleDB 저장 실패: {}", snapshot, e);
        }
    }
}
