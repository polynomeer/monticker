package com.monticker.api.brokerage

import com.monticker.api.brokerage.application.LATEST_CLOSE_BY_SYMBOL_SQL
import com.monticker.api.brokerage.application.STOCK_ID_BY_SYMBOL_SQL
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

/**
 * 증권사 경로의 "코드 → 종목" 조회. `stocks`의 유니크 키는 (symbol, market)이라 이전상장이면 같은 코드가 두 행이다.
 * 예전 `queryForObject("SELECT id FROM stocks WHERE symbol = ?")`는 두 행이면 예외 → 호출부가 삼켜 null(실주문 거절,
 * 리스크 스냅샷에서 보유 종목 누락)이었다.
 */
class StockSymbolLookupIntegrationTest : PostgresIntegrationTest() {

    private val code = "S%05d".format(System.nanoTime() % 100_000)

    private fun stock(market: String, active: Boolean): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange, is_active) VALUES (?, ?, ?, 'KRX', ?) RETURNING id",
        Long::class.java, code, "$code-$market", market, active,
    )!!

    private fun candle(stockId: Long, close: String, at: String) {
        jdbcTemplate.update(
            "INSERT INTO candles_1m (stock_id, open, high, low, close, volume, candle_time) VALUES (?, ?, ?, ?, ?, 1, ?)",
            stockId, BigDecimal(close), BigDecimal(close), BigDecimal(close), BigDecimal(close), Timestamp.from(Instant.parse(at)),
        )
    }

    private fun idOf() = jdbcTemplate.queryForObject(STOCK_ID_BY_SYMBOL_SQL, Long::class.java, code)

    @Test
    fun `two active rows resolve to the newer one, and its own latest close`() {
        val old = stock("KOSDAQ", active = true)
        val new = stock("KOSPI", active = true)
        candle(old, "100", "2026-10-09T06:00:00Z")   // 옛 행이 더 최근 봉을 가져도
        candle(new, "200", "2026-10-09T05:00:00Z")

        assertThat(idOf()).isEqualTo(new)
        assertThat(jdbcTemplate.queryForObject(LATEST_CLOSE_BY_SYMBOL_SQL, BigDecimal::class.java, code)).isEqualByComparingTo("200")
    }

    @Test
    fun `an active row wins over a newer inactive one`() {
        val active = stock("KOSPI", active = true)
        stock("KOSDAQ", active = false)

        assertThat(idOf()).isEqualTo(active)
    }

    @Test
    fun `a single row resolves as before`() {
        val only = stock("KOSPI", active = true)

        assertThat(idOf()).isEqualTo(only)
    }
}
