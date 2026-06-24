package com.monticker.api.backtest.application

import com.monticker.api.backtest.domain.*
import com.monticker.api.common.tracing.Tracing
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

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

            val candles = Tracing.span("backtest.loadCandles", mapOf("stockId" to request.stockId)) { _ ->
                jdbc.query(
                    """SELECT DATE(candle_time AT TIME ZONE 'Asia/Seoul') AS d,
                              open, high, low, close, volume
                       FROM candles_1d WHERE stock_id = ? ORDER BY d""",
                    { rs, _ -> DailyCandle(
                        date   = rs.getDate("d").toLocalDate(),
                        open   = rs.getBigDecimal("open"),
                        high   = rs.getBigDecimal("high"),
                        low    = rs.getBigDecimal("low"),
                        close  = rs.getBigDecimal("close"),
                        volume = rs.getLong("volume"),
                    )},
                    request.stockId,
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
