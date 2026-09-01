package com.monticker.api.backtest.application

import com.monticker.api.backtest.domain.*
import com.monticker.api.common.tracing.Tracing
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service
class BacktestService(private val jdbc: JdbcTemplate) {

    fun run(request: BacktestRequest): BacktestResult {
        return Tracing.span("backtest.run", mapOf(
            "backtest.stockId"  to request.stockId,
            "backtest.strategy" to request.strategy.name,
            "backtest.fromDate" to request.fromDate.toString(),
            "backtest.toDate"   to request.toDate.toString(),
        )) { span ->
            val info   = jdbc.queryForMap("SELECT symbol, name FROM stocks WHERE id = ?", request.stockId)
            val symbol = info["symbol"] as String
            span.setAttribute("backtest.symbol", symbol)

            val maxRange = request.fromDate.plusYears(2)
            require(!request.toDate.isAfter(maxRange)) { "백테스트 기간은 최대 2년입니다" }
            require(!request.toDate.isBefore(request.fromDate)) { "종료일이 시작일보다 빠릅니다" }

            // candles_1d의 "오늘" 행은 장중 계속 바뀌는 미확정 값이다 — 백테스트가 확정된
            // 종가만 쓰도록 요청 범위와 무관하게 오늘 이전으로 한 번 더 clip한다.
            val todayStartKst = Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
            val candles = Tracing.span("backtest.loadCandles", mapOf("stockId" to request.stockId)) { _ ->
                jdbc.query(
                    """SELECT DATE(candle_time AT TIME ZONE 'Asia/Seoul') AS d,
                              open, high, low, close, volume
                       FROM candles_1d
                       WHERE stock_id = ?
                         AND candle_time >= ? AND candle_time <= ?
                         AND candle_time < ?
                       ORDER BY d""",
                    { rs, _ -> DailyCandle(
                        date   = rs.getDate("d").toLocalDate(),
                        open   = rs.getBigDecimal("open"),
                        high   = rs.getBigDecimal("high"),
                        low    = rs.getBigDecimal("low"),
                        close  = rs.getBigDecimal("close"),
                        volume = rs.getLong("volume"),
                    )},
                    request.stockId,
                    java.sql.Date.valueOf(request.fromDate),
                    java.sql.Date.valueOf(request.toDate),
                    java.sql.Timestamp.from(todayStartKst),
                )
            }

            require(candles.size >= 10) { "캔들 데이터 부족: ${candles.size}개 (최소 10개 필요)" }
            span.setAttribute("backtest.candleCount", candles.size.toLong())

            val result = Tracing.span("backtest.simulate") { _ ->
                BacktestEngine.run(candles, request, symbol)
            }
            span.setAttribute("backtest.tradeCount",  result.trades.size.toLong())
            span.setAttribute("backtest.totalReturn",  result.metrics.totalReturn)
            result
        }
    }
}
