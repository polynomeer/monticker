package com.monticker.api.matching

import com.monticker.api.common.time.KstPeriod
import com.monticker.api.matching.application.ExecutionQualityService
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/**
 * ADR-091 — 체결 품질 집계 SQL(V88 컬럼, 사용자 범위, KST 기간 경계). JVM 시간대와 무관해야 한다
 * (`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`로도 돌린다).
 */
class ExecutionQualityIntegrationTest : PostgresIntegrationTest() {

    private val service = ExecutionQualityService(jdbcTemplate)

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "eq-${System.nanoTime()}@test.local", "eq",
    )!!

    private fun stock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, ?, 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "EQ${System.nanoTime() % 1_000_000}", "체결품질",
    )!!

    private fun orderWithFill(
        userId: Long, stockId: Long, side: String, fill: String, filledAt: Instant,
        bid: String?, ask: String?, submittedAt: Instant?, type: String = "MARKET", qty: Int = 1,
    ) {
        val orderId = jdbcTemplate.queryForObject(
            """INSERT INTO orders (user_id, stock_id, side, order_type, quantity, filled_qty, status, quote_bid, quote_ask, quote_at, quote_source, submitted_at)
               VALUES (?, ?, ?, ?, ?, ?, 'FILLED', ?, ?, ?, ?, ?) RETURNING id""",
            Long::class.java, userId, stockId, side, type, qty, qty,
            bid?.let(::BigDecimal), ask?.let(::BigDecimal), submittedAt?.let(Timestamp::from),
            if (bid != null || ask != null) "KIS_REALTIME" else null, submittedAt?.let(Timestamp::from),
        )!!
        jdbcTemplate.update(
            """INSERT INTO fills (order_id, user_id, stock_id, side, quantity, fill_price, amount, filled_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            orderId, userId, stockId, side, qty, BigDecimal(fill), BigDecimal(fill).multiply(BigDecimal(qty)), Timestamp.from(filledAt),
        )
    }

    @Test
    fun `aggregates only the user's fills inside the KST period and reports fills without a quote`() {
        val me = newUser()
        val other = newUser()
        val s = stock()
        val day = LocalDate.of(2026, 10, 6)
        val dayStart = KstPeriod.startOf(day) // 2026-10-05T15:00Z

        // 기간 안(KST 10/6 00:00 정각 — 포함)
        orderWithFill(me, s, "BUY", "10020", dayStart, "9990", "10010", dayStart.minusMillis(5))
        // 기간 안, 매도 가격 개선
        orderWithFill(me, s, "SELL", "10000", dayStart.plusSeconds(3600), "9990", "10010", dayStart.plusSeconds(3600).minusMillis(3))
        // 기간 안, V88 이전 주문(호가·접수 시각 없음)
        orderWithFill(me, s, "BUY", "10000", dayStart.plusSeconds(7200), null, null, null)
        // 기간 밖(KST 10/7 00:00 — 제외)
        orderWithFill(me, s, "BUY", "20000", KstPeriod.startOf(day.plusDays(1)), "9990", "10010", KstPeriod.startOf(day.plusDays(1)))
        // 기간 밖(KST 10/5 23:59:59.999)
        orderWithFill(me, s, "BUY", "20000", dayStart.minusMillis(1), "9990", "10010", dayStart.minusMillis(2))
        // 다른 사용자
        orderWithFill(other, s, "BUY", "20000", dayStart.plusSeconds(10), "9990", "10010", dayStart)

        val r = service.summary(me, KstPeriod(day, day))

        assertThat(r.slippage.fillCount).isEqualTo(2)
        assertThat(r.excludedNoQuote).isEqualTo(1)
        assertThat(r.slippageBySide["BUY"]!!.avgBps!!).isCloseTo(9.99, within(0.01))
        assertThat(r.slippageBySide["SELL"]!!.avgBps!!).isCloseTo(-10.01, within(0.01))
        assertThat(r.latency.orderCount).isEqualTo(2)
        assertThat(r.latency.avgMs!!).isCloseTo(4.0, within(1e-6))
        assertThat(r.latency.excludedNoSubmitTime).isEqualTo(1)
    }

    @Test
    fun `a user with no fills gets empty stats`() {
        val r = service.summary(newUser(), KstPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7)))
        assertThat(r.slippage.avgBps).isNull()
        assertThat(r.latency.avgMs).isNull()
        assertThat(r.excludedNoQuote).isZero()
    }
}
