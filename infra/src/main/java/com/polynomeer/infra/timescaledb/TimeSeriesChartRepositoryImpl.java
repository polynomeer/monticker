package com.polynomeer.infra.timescaledb;

import com.polynomeer.domain.price.model.ChartPoint;
import com.polynomeer.domain.price.repository.TimeSeriesChartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class TimeSeriesChartRepositoryImpl implements TimeSeriesChartRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<ChartPoint> findChart(String tickerCode, String interval, ZonedDateTime from, ZonedDateTime to) {
        String sql = """
                    SELECT
                      time_bucket(?::interval, "timestamp") AS bucket,
                      first(price, "timestamp") AS open,
                      max(price) AS high,
                      min(price) AS low,
                      last(price, "timestamp") AS close,
                      sum(volume) AS volume
                    FROM price_history
                    WHERE ticker_code = ?
                      AND "timestamp" BETWEEN ? AND ?
                    GROUP BY bucket
                    ORDER BY bucket
                """;

        String pgInterval = toPgInterval(interval);
        OffsetDateTime fromTs = from.toOffsetDateTime();
        OffsetDateTime toTs = to.toOffsetDateTime();

        return jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setString(1, pgInterval);
            ps.setString(2, tickerCode);
            ps.setObject(3, fromTs);
            ps.setObject(4, toTs);
            return ps;
        }, (rs, i) -> new ChartPoint(
                rs.getObject("bucket", OffsetDateTime.class).toZonedDateTime(),
                rs.getLong("open"),
                rs.getLong("high"),
                rs.getLong("low"),
                rs.getLong("close"),
                rs.getLong("volume")
        ));
    }

    private String toPgInterval(String s) {
        if (s == null || s.isEmpty()) throw new IllegalArgumentException("interval required");
        s = s.trim().toLowerCase();
        if (s.endsWith("m")) {
            String n = s.substring(0, s.length() - 1);
            return n + (n.equals("1") ? " minute" : " minutes");
        } else if (s.endsWith("h")) {
            String n = s.substring(0, s.length() - 1);
            return n + (n.equals("1") ? " hour" : " hours");
        } else if (s.endsWith("d")) {
            String n = s.substring(0, s.length() - 1);
            return n + (n.equals("1") ? " day" : " days");
        } else {
            return s;
        }
    }
}
