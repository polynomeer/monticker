package com.monticker.api.backtest.application

import com.monticker.api.backtest.domain.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

@Service
class BacktestService(private val jdbc: JdbcTemplate) {

    fun run(request: BacktestRequest): BacktestResult {
        val info = jdbc.queryForMap("SELECT symbol, name FROM stocks WHERE id = ?", request.stockId)
        val symbol = info["symbol"] as String

        val candles = jdbc.query(
            """
            SELECT DATE(candle_time AT TIME ZONE 'Asia/Seoul') AS d,
                   open, high, low, close, volume
            FROM candles_1d
            WHERE stock_id = ?
            ORDER BY d
            """,
            { rs, _ ->
                DailyCandle(
                    date   = rs.getDate("d").toLocalDate(),
                    open   = rs.getBigDecimal("open"),
                    high   = rs.getBigDecimal("high"),
                    low    = rs.getBigDecimal("low"),
                    close  = rs.getBigDecimal("close"),
                    volume = rs.getLong("volume"),
                )
            },
            request.stockId,
        )

        require(candles.size >= 10) { "캔들 데이터 부족: ${candles.size}개 (최소 10개 필요)" }
        return BacktestEngine.run(candles, request, symbol)
    }
}
