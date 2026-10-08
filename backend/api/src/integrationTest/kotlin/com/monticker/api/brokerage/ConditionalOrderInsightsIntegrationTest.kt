package com.monticker.api.brokerage

import com.monticker.api.brokerage.application.ConditionalOrderInsights
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

/**
 * 조건부 주문 통계·발동 기록·일괄 시세 SQL을 실제 Postgres에서. 다른 사용자의 행·주문이 섞이지 않는지, 이번 달 경계가 KST인지
 * (JVM 타임존과 무관하게 — `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`로도 돌린다).
 */
class ConditionalOrderInsightsIntegrationTest : PostgresIntegrationTest() {

    private val insights = ConditionalOrderInsights(jdbcTemplate)

    private class Fixture(val userId: Long, val accountId: Long)

    private fun fixture(): Fixture {
        val userId = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, 'i') RETURNING id", Long::class.java, "ins-${System.nanoTime()}@test.local",
        )!!
        val accountId = jdbcTemplate.queryForObject(
            "INSERT INTO brokerage_accounts (user_id, provider, account_number) VALUES (?, 'KIS', ?) RETURNING id",
            Long::class.java, userId, "I${System.nanoTime() % 100000000}",
        )!!
        return Fixture(userId, accountId)
    }

    private fun stock(name: String = "인사이트"): Pair<Long, String> {
        val symbol = "I${System.nanoTime() % 100000000}"
        val id = jdbcTemplate.queryForObject(
            "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, ?, 'KOSPI', 'KRX') RETURNING id",
            Long::class.java, symbol, name,
        )!!
        return id to symbol
    }

    private fun conditional(
        f: Fixture, stockId: Long, symbol: String, status: String, triggeredAt: Instant? = null,
        executedOrderId: Long? = null, createdAt: Instant = Instant.now(), failReason: String? = null,
    ): Long = jdbcTemplate.queryForObject(
        """
        INSERT INTO conditional_orders (user_id, account_id, stock_id, symbol, side, trigger_type, trigger_price, order_type, quantity,
                                        status, triggered_at, executed_order_id, created_at, fail_reason)
        VALUES (?, ?, ?, ?, 'SELL', 'STOP_LOSS', 70000, 'MARKET', 3, ?, ?, ?, ?, ?) RETURNING id
        """.trimIndent(),
        Long::class.java, f.userId, f.accountId, stockId, symbol, status, triggeredAt?.let(Timestamp::from), executedOrderId,
        Timestamp.from(createdAt), failReason,
    )!!

    private fun brokerOrder(f: Fixture, status: String, filled: Int, avg: String?): Long = jdbcTemplate.queryForObject(
        """
        INSERT INTO brokerage_orders (user_id, account_id, symbol, side, order_type, quantity, filled_qty, avg_fill_price, status)
        VALUES (?, ?, 'X', 'SELL', 'MARKET', 3, ?, ?, ?) RETURNING id
        """.trimIndent(),
        Long::class.java, f.userId, f.accountId, filled, avg?.let(::BigDecimal), status,
    )!!

    @Test
    fun `stats count every status for this user only and fired this month from the KST month start`() {
        val me = fixture(); val other = fixture()
        val (s, sym) = stock()
        // 이번 달 1일 00:00 KST 직전·직후 — UTC로 달을 셌다면 9시간 어긋난다
        val now = Instant.parse("2026-10-08T03:00:00Z")
        val monthStartKst = Instant.parse("2026-09-30T15:00:00Z")
        conditional(me, s, sym, "ACTIVE")
        conditional(me, s, sym, "ACTIVE")
        conditional(me, s, sym, "EXECUTED", triggeredAt = monthStartKst.plusSeconds(60))
        conditional(me, s, sym, "FAILED", triggeredAt = monthStartKst.minusSeconds(60))   // 9월 30일 23:59 KST — 지난달
        conditional(me, s, sym, "EXPIRED")
        conditional(other, s, sym, "ACTIVE")
        conditional(other, s, sym, "EXECUTED", triggeredAt = monthStartKst.plusSeconds(120))

        val stats = insights.stats(me.userId, now)

        assertThat(stats.byStatus).containsEntry("ACTIVE", 2L).containsEntry("EXECUTED", 1L).containsEntry("FAILED", 1L)
            .containsEntry("EXPIRED", 1L).containsEntry("CANCELLED", 0L).containsEntry("TRIGGERED", 0L)
        assertThat(stats.total).isEqualTo(5L)
        assertThat(stats.firedThisMonth).isEqualTo(1L)
        assertThat(stats.monthStart).isEqualTo(monthStartKst)
    }

    @Test
    fun `trigger log lists only fired orders newest first with the linked order state, never another user's order`() {
        val me = fixture(); val other = fixture()
        val (s, sym) = stock()
        val t0 = Instant.parse("2026-10-07T01:00:00Z")
        val filled = brokerOrder(me, "FILLED", 3, "69900")
        val othersOrder = brokerOrder(other, "FILLED", 3, "1")
        val executed = conditional(me, s, sym, "EXECUTED", triggeredAt = t0, executedOrderId = filled)
        val failed = conditional(me, s, sym, "FAILED", triggeredAt = t0.plusSeconds(60), failReason = "증권사 거부")
        // 데이터가 어긋나 남의 주문을 가리키더라도 조인하지 않는다
        val crossed = conditional(me, s, sym, "TRIGGERED", triggeredAt = t0.plusSeconds(120), executedOrderId = othersOrder)
        conditional(me, s, sym, "ACTIVE")                                       // 발동 안 함
        conditional(me, s, sym, "CANCELLED")                                    // 발동 안 함
        conditional(other, s, sym, "EXECUTED", triggeredAt = t0.plusSeconds(30), executedOrderId = othersOrder)

        val page = insights.triggers(me.userId, 0, 2)

        assertThat(page.totalElements).isEqualTo(3L)
        assertThat(page.totalPages).isEqualTo(2)
        assertThat(page.content.map { it.conditionalOrderId }).containsExactly(crossed, failed)
        assertThat(page.content[0].orderStatus).isNull()
        assertThat(page.content[1].failReason).isEqualTo("증권사 거부")
        assertThat(page.content[1].executedOrderId).isNull()

        val last = insights.triggers(me.userId, 1, 2).content.single()
        assertThat(last.conditionalOrderId).isEqualTo(executed)
        assertThat(last.orderStatus).isEqualTo("FILLED")
        assertThat(last.filledQty).isEqualTo(3)
        assertThat(last.avgFillPrice).isEqualByComparingTo("69900")

        assertThat(insights.triggers(me.userId, 0, 10_000).size).isEqualTo(ConditionalOrderInsights.MAX_PAGE_SIZE)
    }

    @Test
    fun `quotes cover my active order stocks in one query with the latest one-minute close`() {
        val me = fixture(); val other = fixture()
        val (a, symA) = stock("가")
        val (b, symB) = stock("나")
        val (c, symC) = stock("다")
        conditional(me, a, symA, "ACTIVE", createdAt = Instant.now().minusSeconds(100))
        conditional(me, a, symA, "ACTIVE", createdAt = Instant.now().minusSeconds(50))
        conditional(me, b, symB, "ACTIVE", createdAt = Instant.now())
        conditional(me, c, symC, "EXPIRED")                                     // 감시 중 아님
        conditional(other, c, symC, "ACTIVE")                                   // 남의 것
        listOf(70000 to 2, 70500 to 1).forEach { (price, minsAgo) ->
            jdbcTemplate.update(
                "INSERT INTO candles_1m (stock_id, open, high, low, close, volume, candle_time) VALUES (?, ?, ?, ?, ?, 1, date_trunc('minute', now()) - make_interval(mins => ?))",
                a, price, price, price, price, minsAgo,
            )
        }

        val quotes = insights.quotes(me.userId)

        assertThat(quotes.map { it.symbol }).containsExactly(symB, symA)
        assertThat(quotes.first { it.stockId == a }.price).isEqualByComparingTo("70500")
        assertThat(quotes.first { it.stockId == a }.name).isEqualTo("가")
        assertThat(quotes.first { it.stockId == b }.price).isNull()             // 시세 없음 → null(0이 아니라)
    }
}
