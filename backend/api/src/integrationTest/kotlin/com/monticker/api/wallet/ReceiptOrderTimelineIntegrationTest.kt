package com.monticker.api.wallet

import com.monticker.api.support.PostgresIntegrationTest
import com.monticker.api.wallet.application.ReceiptOrderTimelineQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-096 — 영수증의 주문 진행 기록(V93 reserved_at, 부분 체결, 취소, V88·V93 이전 행) SQL을 실제 Postgres에서 검증한다.
 * JVM 시간대와 무관해야 한다(`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`로도 돌린다).
 */
class ReceiptOrderTimelineIntegrationTest : PostgresIntegrationTest() {

    private val query = ReceiptOrderTimelineQuery(jdbcTemplate)
    private val t0 = Instant.parse("2026-10-08T00:30:00Z") // KST 09:30

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "rt-${System.nanoTime()}@test.local", "rt",
    )!!

    private fun stock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, ?, 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "RT${System.nanoTime() % 1_000_000}", "영수증",
    )!!

    private fun order(
        userId: Long, stockId: Long, side: String, type: String, qty: Int, limit: String?, filled: Int, status: String,
        submittedAt: Instant?, reservedAt: Instant?, createdAt: Instant = t0, updatedAt: Instant = t0, rejectReason: String? = null,
    ): Long = jdbcTemplate.queryForObject(
        """INSERT INTO orders (user_id, stock_id, side, order_type, quantity, limit_price, filled_qty, status,
                               submitted_at, reserved_at, created_at, updated_at, reject_reason)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
        Long::class.java, userId, stockId, side, type, qty, limit?.let(::BigDecimal), filled, status,
        submittedAt?.let(Timestamp::from), reservedAt?.let(Timestamp::from), Timestamp.from(createdAt), Timestamp.from(updatedAt), rejectReason,
    )!!

    /** 체결 1건 + 그 체결의 모의 거래(paper_trades.fill_id). 거래 id를 돌려준다. */
    private fun fillWithTrade(orderId: Long, userId: Long, stockId: Long, side: String, qty: Int, price: String, at: Instant): Long {
        val amount = BigDecimal(price).multiply(BigDecimal(qty))
        val fillId = jdbcTemplate.queryForObject(
            """INSERT INTO fills (order_id, user_id, stock_id, side, quantity, fill_price, amount, filled_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
            Long::class.java, orderId, userId, stockId, side, qty, BigDecimal(price), amount, Timestamp.from(at),
        )!!
        return jdbcTemplate.queryForObject(
            """INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, traded_at, fill_id)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
            Long::class.java, userId, stockId, side, qty, BigDecimal(price), amount, Timestamp.from(at), fillId,
        )!!
    }

    @Test
    fun `a filled BUY LIMIT shows submit, reservation of limit x qty, and its fill`() {
        val me = newUser(); val s = stock()
        val o = order(me, s, "BUY", "LIMIT", 10, "70000", 10, "FILLED", t0, t0.plusMillis(3))
        val trade = fillWithTrade(o, me, s, "BUY", 10, "69800", t0.plusSeconds(120))

        val r = query.find(me, trade)!!

        assertThat(r.orderType).isEqualTo("LIMIT")
        assertThat(r.submittedAt).isEqualTo(t0)
        assertThat(r.reservedAt).isEqualTo(t0.plusMillis(3))
        assertThat(r.reservedAmount).isEqualByComparingTo("700000")
        assertThat(r.reservedQty).isNull()
        assertThat(r.cancelledAt).isNull()
        assertThat(r.fills).hasSize(1)
        assertThat(r.fills.single().thisTrade).isTrue()
        assertThat(r.fills.single().filledAt).isEqualTo(t0.plusSeconds(120))
    }

    @Test
    fun `partial fills list every fill of the order in time order and mark this trade's one`() {
        val me = newUser(); val s = stock()
        val o = order(me, s, "BUY", "LIMIT", 10, "500", 7, "PARTIALLY_FILLED", t0, t0)
        val first = fillWithTrade(o, me, s, "BUY", 3, "500", t0.plusSeconds(10))
        val second = fillWithTrade(o, me, s, "BUY", 4, "495", t0.plusSeconds(20))

        val r = query.find(me, second)!!

        assertThat(r.status).isEqualTo("PARTIALLY_FILLED")
        assertThat(r.filledQty).isEqualTo(7)
        assertThat(r.fills.map { it.quantity }).containsExactly(3, 4)
        assertThat(r.fills.map { it.thisTrade }).containsExactly(false, true)
        assertThat(query.find(me, first)!!.fills.map { it.thisTrade }).containsExactly(true, false)
    }

    @Test
    fun `an order cancelled after a partial fill carries the cancel time and the engine's reason`() {
        val me = newUser(); val s = stock()
        val cancelAt = t0.plusSeconds(300)
        val o = order(me, s, "SELL", "LIMIT", 5, "1200", 2, "CANCELLED", t0, t0, updatedAt = cancelAt, rejectReason = "체결 시점 보유 수량 부족: 보유 0, 잔량 3")
        val trade = fillWithTrade(o, me, s, "SELL", 2, "1200", t0.plusSeconds(60))

        val r = query.find(me, trade)!!

        assertThat(r.cancelledAt).isEqualTo(cancelAt)
        assertThat(r.cancelReason).contains("보유 수량 부족")
        assertThat(r.reservedQty).isEqualTo(5)
        assertThat(r.reservedAmount).isNull()
    }

    @Test
    fun `a market order has no reservation figures and old rows fall back to created_at with no reservation time`() {
        val me = newUser(); val s = stock()
        val market = order(me, s, "BUY", "MARKET", 1, null, 1, "FILLED", t0, t0)
        val mTrade = fillWithTrade(market, me, s, "BUY", 1, "1000", t0)
        val old = order(me, s, "BUY", "LIMIT", 2, "900", 2, "FILLED", submittedAt = null, reservedAt = null, createdAt = t0.minusSeconds(86_400))
        val oTrade = fillWithTrade(old, me, s, "BUY", 2, "900", t0)

        val m = query.find(me, mTrade)!!
        assertThat(m.orderType).isEqualTo("MARKET")
        assertThat(m.reservedAmount).isNull()
        assertThat(m.reservedQty).isNull()

        val legacy = query.find(me, oTrade)!!
        assertThat(legacy.submittedAt).isEqualTo(t0.minusSeconds(86_400))
        assertThat(legacy.reservedAt).isNull()
        assertThat(legacy.reservedAmount).isEqualByComparingTo("1800")
    }

    @Test
    fun `pre ADR-047 trades without a fill link and other users' trades return nothing`() {
        val me = newUser(); val other = newUser(); val s = stock()
        val legacyTrade = jdbcTemplate.queryForObject(
            "INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount) VALUES (?, ?, 'BUY', 1, 1000, 1000) RETURNING id",
            Long::class.java, me, s,
        )!!
        assertThat(query.find(me, legacyTrade)).isNull()

        val o = order(other, s, "BUY", "LIMIT", 1, "100", 1, "FILLED", t0, t0)
        val theirs = fillWithTrade(o, other, s, "BUY", 1, "100", t0)
        assertThat(query.find(me, theirs)).isNull()
    }
}
