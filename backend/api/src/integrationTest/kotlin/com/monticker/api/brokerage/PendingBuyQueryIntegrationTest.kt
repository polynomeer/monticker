package com.monticker.api.brokerage

import com.monticker.api.brokerage.application.PendingBuyQuery
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/** ADR-058 — "아직 보유 내역에 없을 수 있는 매수" 집계 SQL을 실제 Postgres에서 상태·시각 조합별로 확인한다. */
class PendingBuyQueryIntegrationTest : PostgresIntegrationTest() {

    private val query by lazy { PendingBuyQuery(jdbcTemplate) }

    private fun account(): Long {
        val userId = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, 'p') RETURNING id", Long::class.java, "p58-${System.nanoTime()}@test.local",
        )!!
        return jdbcTemplate.queryForObject(
            "INSERT INTO brokerage_accounts (user_id, provider, account_number) VALUES (?, 'KIS', ?) RETURNING id",
            Long::class.java, userId, "P${System.nanoTime() % 100000000}",
        )!!
    }

    private fun stock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, 'p', 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "Q${System.nanoTime() % 100000000}",
    )!!

    private fun order(
        accountId: Long, stockId: Long, status: String, qty: Int, filled: Int = 0, side: String = "BUY",
        submittedAt: Instant = Instant.now(), filledAt: Instant? = null,
    ) {
        val userId = jdbcTemplate.queryForObject("SELECT user_id FROM brokerage_accounts WHERE id = ?", Long::class.java, accountId)
        jdbcTemplate.update(
            """
            INSERT INTO brokerage_orders (user_id, account_id, stock_id, symbol, side, order_type, quantity, filled_qty, status, submitted_at, filled_at)
            VALUES (?, ?, ?, 'X', ?, 'LIMIT', ?, ?, ?, ?, ?)
            """.trimIndent(),
            userId, accountId, stockId, side, qty, filled, status, Timestamp.from(submittedAt), filledAt?.let { Timestamp.from(it) },
        )
    }

    @Test
    fun `상태·시각별로 셀 것만 센다`() {
        val acc = account(); val s = stock(); val now = Instant.now()
        val twoDaysAgo = now.minus(Duration.ofDays(2))

        order(acc, s, "UNKNOWN", 7, submittedAt = twoDaysAgo)                 // 날짜 무관 → 7
        order(acc, s, "PENDING_SUBMIT", 3)                                     // → 3
        order(acc, s, "SUBMITTED", 10)                                         // 오늘 미체결 → 10
        order(acc, s, "PARTIALLY_FILLED", 10, filled = 4, filledAt = now)      // 잔량 6 + 막 체결 4 → 10
        order(acc, s, "SUBMITTED", 100, submittedAt = twoDaysAgo)              // 24시간 넘은 미체결(증권사에서 소멸) → 0
        order(acc, s, "SUBMITTED", 2, submittedAt = now.minus(Duration.ofHours(20)))   // 어젯밤 장 마감 후 접수 — 다음 세션에 살아 있을 수 있다 → 2
        order(acc, s, "FILLED", 5, filled = 5, filledAt = now.minusSeconds(30))   // 체결 2분 이내 → 5
        order(acc, s, "FILLED", 50, filled = 50, filledAt = now.minusSeconds(600)) // 이미 잔고 반영 → 0
        order(acc, s, "REJECTED", 9)                                           // → 0
        order(acc, s, "SUBMITTED", 20, side = "SELL")                          // 매도는 세지 않는다 → 0

        assertThat(query.pendingBuys(acc, now)).isEqualTo(mapOf(s to 7 + 3 + 10 + 2 + 10 + 5))
    }

    @Test
    fun `다른 계좌의 주문은 섞이지 않고, 0인 종목은 빠진다`() {
        val a = account(); val b = account(); val s1 = stock(); val s2 = stock()
        order(a, s1, "SUBMITTED", 4)
        order(b, s1, "SUBMITTED", 99)
        order(a, s2, "REJECTED", 8)

        assertThat(query.pendingBuys(a)).isEqualTo(mapOf(s1 to 4))
    }
}
