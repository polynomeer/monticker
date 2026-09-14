package com.monticker.api.risk

import com.monticker.api.risk.application.RiskRuleQueryService
import com.monticker.api.risk.domain.RiskLimit
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

/**
 * CH-05에서 발견: DailyLossRule의 "일간 손익"이 `SUM(SELL − BUY)` 현금 흐름이라 매수가 손실로 잡혔다.
 * 실제 SQL을 실제 Postgres에서 검증한다 — 평단가 계산은 mock으로 못 본다. ADR-047 이후 실행 기록은 paper_trades 하나다
 * (매칭 엔진 체결은 PaperExecutionListener가 미러링한다) — fills 는 더 이상 리스크 판정의 입력이 아니다.
 */
class RiskRuleQueryServiceIntegrationTest : PostgresIntegrationTest() {

    private val service = RiskRuleQueryService(jdbcTemplate)
    private val limits = RiskLimit(userId = 0L)   // 기본 한도: 일간 손실 3%, 집중도 30%, 종목 10개, 시간당 5건

    private fun newUser(cash: String = "10000000"): Long {
        val id = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
            Long::class.java, "risk-${System.nanoTime()}@test.local", "risk",
        )!!
        jdbcTemplate.update("INSERT INTO paper_accounts (user_id, cash) VALUES (?, ?)", id, BigDecimal(cash))
        return id
    }
    private fun stocks(n: Int): List<Long> = jdbcTemplate.queryForList("SELECT id FROM stocks ORDER BY id LIMIT $n", Long::class.java)

    private fun fill(userId: Long, stockId: Long, side: String, qty: Int, price: String, at: Instant = Instant.now()) {
        val orderId = jdbcTemplate.queryForObject(
            "INSERT INTO orders (user_id, stock_id, side, order_type, quantity, status) VALUES (?, ?, ?, 'MARKET', ?, 'FILLED') RETURNING id",
            Long::class.java, userId, stockId, side, qty,
        )!!
        jdbcTemplate.update(
            "INSERT INTO fills (order_id, user_id, stock_id, side, quantity, fill_price, amount, filled_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            orderId, userId, stockId, side, qty, BigDecimal(price), BigDecimal(price).multiply(BigDecimal(qty)), Timestamp.from(at),
        )
    }
    private fun paperTrade(userId: Long, stockId: Long, side: String, qty: Int, price: String, at: Instant = Instant.now()) {
        jdbcTemplate.update(
            "INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, traded_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            userId, stockId, side, qty, BigDecimal(price), BigDecimal(price).multiply(BigDecimal(qty)), Timestamp.from(at),
        )
    }
    private fun rule(userId: Long, stockId: Long, name: String) =
        service.evaluate(userId, stockId, "BUY", 1, BigDecimal("100"), limits).first { it.rule == name }

    @Test
    fun `buying all day is not a loss — the old cash-flow formula blocked the sixth buy`() {
        val userId = newUser(); val s = stocks(1)[0]
        repeat(6) { paperTrade(userId, s, "BUY", 1, "65000") }   // 390,000 > 3% of 10,000,000

        val r = rule(userId, s, "DailyLossRule")

        assertThat(r.current).isEqualTo(0.0)
        assertThat(r.passed).isTrue()
    }

    @Test
    fun `realized loss is sold quantity times sell price minus the moving-average cost`() {
        val userId = newUser(); val s = stocks(1)[0]
        val yesterday = Instant.now().minusSeconds(36 * 3600)
        paperTrade(userId, s, "BUY", 10, "100", yesterday)    // 1,000
        paperTrade(userId, s, "BUY", 10, "120", yesterday)    // 1,200  → 평단가 110
        paperTrade(userId, s, "SELL", 5, "90")                // 오늘: 5 × (90 − 110) = −100

        val r = rule(userId, s, "DailyLossRule")

        assertThat(r.current).isEqualTo(-100.0)
        assertThat(r.passed).isTrue()   // 한도 −300,000
    }

    @Test
    fun `a realized loss beyond the daily limit blocks, a sale before today does not count`() {
        val userId = newUser(); val s = stocks(1)[0]
        val yesterday = Instant.now().minusSeconds(36 * 3600)
        paperTrade(userId, s, "BUY", 100, "10000", yesterday)   // 평단가 10,000
        paperTrade(userId, s, "SELL", 50, "9000", yesterday)    // 어제의 손실 −50,000 — 오늘 판정과 무관
        paperTrade(userId, s, "SELL", 40, "2000")               // 오늘: 40 × (2,000 − 10,000) = −320,000 > 3%

        val r = rule(userId, s, "DailyLossRule")

        assertThat(r.current).isEqualTo(-320000.0)
        assertThat(r.passed).isFalse()
    }

    @Test
    fun `a matching-engine fill without its paper_trades mirror is not a holding — the mirror is the record`() {
        val userId = newUser(); val (a, b) = stocks(2)
        paperTrade(userId, a, "BUY", 10, "100")                // 계좌 실행 기록이 있는 종목
        fill(userId, b, "BUY", 5, "100")                       // fills만 있고 미러가 없다(ADR-047 이전 데이터 형태) → 판정 밖

        val checks = service.evaluate(userId, b, "BUY", 1, BigDecimal("100"), limits)

        // b는 (미러가 없어) 신규 종목이므로 PositionCountRule이 평가되고, 현재 보유 종목 수는 a 하나다
        assertThat(checks.first { it.rule == "PositionCountRule" }.current).isEqualTo(1.0)
    }
}
