package com.monticker.api.paper

import com.monticker.api.common.domain.Money
import com.monticker.api.paper.application.PaperPortfolioQueryService
import com.monticker.api.paper.domain.PaperAccount
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import com.monticker.api.support.PostgresIntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional

/**
 * /paper/portfolio의 총 평가금액은 /wallet 총자산과 같은 정의 — 현금 + 미체결 매수 예약금 + 보유 평가액(ADR-043).
 * 로컬 점검(2026-10-09)에서 예약금이 빠져 지정가 매수를 걸자마자 총 평가금액이 예약금만큼 줄어 보였다.
 */
class PaperPortfolioTotalValueIntegrationTest : PostgresIntegrationTest() {

    private val accounts = mockk<PaperAccountRepository>()
    private val service by lazy { PaperPortfolioQueryService(accounts, mockk(), jdbcTemplate) }

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "total-value-${System.nanoTime()}@test.local", "total-value",
    )!!

    private fun stock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, ?, 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "TV${System.nanoTime() % 1_000_000}", "총자산",
    )!!

    private fun order(userId: Long, stockId: Long, side: String, price: String, qty: Int, filled: Int, status: String) {
        jdbcTemplate.update(
            """INSERT INTO orders (user_id, stock_id, side, order_type, quantity, limit_price, filled_qty, status)
               VALUES (?, ?, ?, 'LIMIT', ?, ?, ?, ?)""",
            userId, stockId, side, qty, BigDecimal(price), filled, status,
        )
    }

    @Test
    fun `total value includes cash reserved by open buy orders and the holdings value`() {
        val user = newUser()
        val held = stock()
        val other = stock()
        every { accounts.findByUserId(user) } returns Optional.of(PaperAccount(userId = user, cash = Money.of(BigDecimal("1000000"))))
        jdbcTemplate.update(
            "INSERT INTO portfolio_positions (user_id, stock_id, net_qty, avg_buy_price, total_cost) VALUES (?, ?, 2, 1000, 2000)",
            user, held,
        )
        jdbcTemplate.update(
            "INSERT INTO candles_1m (stock_id, open, high, low, close, volume, candle_time) VALUES (?, 1500, 1500, 1500, 1500, 1, ?)",
            held, Timestamp.from(Instant.parse("2026-10-09T00:00:00Z")),
        )
        order(user, other, "BUY", "36000", 1, 0, "PENDING")             // 예약 36,000
        order(user, other, "BUY", "1000", 10, 4, "PARTIALLY_FILLED")    // 잔량 6주 × 1,000 = 6,000
        order(user, other, "SELL", "50000", 1, 0, "PENDING")            // 매도는 예약금 없음
        order(user, other, "BUY", "99999", 1, 0, "CANCELLED")           // 끝난 주문

        val p = service.getPortfolio(user)

        assertThat(p.cash).isEqualByComparingTo("1000000")
        // 1,000,000 + (36,000 + 6,000) + 2 × 1,500
        assertThat(p.totalValue).isEqualByComparingTo("1045000")
    }
}
