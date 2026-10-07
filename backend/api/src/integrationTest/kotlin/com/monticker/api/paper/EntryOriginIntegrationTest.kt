package com.monticker.api.paper

import com.monticker.api.matching.submit.OrderOriginType
import com.monticker.api.paper.application.PaperRealizedPnlService
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-085 — 진입 출처 백필(V82)과 출처별 실현 손익을 실제 Postgres에서 검증한다.
 * 백필 SQL은 컨테이너 기동 때 빈 DB에 이미 한 번 돌았다. 여기서는 V81 이전 모양의 행(origin NULL)을 만든 뒤
 * 같은 파일을 다시 실행한다 — V82는 origin IS NULL 행만 건드리므로 재실행이 안전하다(그것도 검증한다).
 */
class EntryOriginIntegrationTest : PostgresIntegrationTest() {

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "origin-${System.nanoTime()}@test.local", "origin",
    )!!

    private fun stockId(offset: Int = 0): Long =
        jdbcTemplate.queryForObject("SELECT id FROM stocks ORDER BY id OFFSET ? LIMIT 1", Long::class.java, offset)!!

    private fun order(userId: Long, stockId: Long, key: String?): Long = jdbcTemplate.queryForObject(
        """INSERT INTO orders (user_id, stock_id, side, order_type, quantity, filled_qty, status, idempotency_key)
           VALUES (?, ?, 'BUY', 'MARKET', 1, 1, 'FILLED', ?) RETURNING id""",
        Long::class.java, userId, stockId, key,
    )!!

    /** 주문 → 체결 → 계좌 기록(fill_id 링크). 출처 컬럼은 비워 둔다(V81 이전 행). */
    private fun tradeFor(orderId: Long, userId: Long, stockId: Long): Long {
        val fillId = jdbcTemplate.queryForObject(
            """INSERT INTO fills (order_id, user_id, stock_id, side, quantity, fill_price, amount)
               VALUES (?, ?, ?, 'BUY', 1, 1000, 1000) RETURNING id""",
            Long::class.java, orderId, userId, stockId,
        )!!
        return jdbcTemplate.queryForObject(
            """INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, fill_id)
               VALUES (?, ?, 'BUY', 1, 1000, 1000, ?) RETURNING id""",
            Long::class.java, userId, stockId, fillId,
        )!!
    }

    private fun originOf(table: String, id: Long): Pair<String?, Long?> = jdbcTemplate.queryForObject(
        "SELECT origin, origin_ref FROM $table WHERE id = ?",
        { rs, _ -> rs.getString("origin") to (rs.getObject("origin_ref") as Number?)?.toLong() }, id,
    )!!

    private fun runBackfill() {
        val sql = ClassPathResource("db/migration/V82__backfill_entry_origin_and_planned_emotion.sql")
            .inputStream.bufferedReader().readText()
        jdbcTemplate.execute(sql)
    }

    @Test
    fun `backfill derives origin only from server-side links and leaves unprovable rows null`() {
        val userId = newUser()
        val stock = stockId()

        // 1) Watch Rule 발동 기록이 가리키는 주문
        val ruleId = jdbcTemplate.queryForObject(
            "INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity) VALUES (?, ?, 'VOLUME_SURGE', 'BUY', 1) RETURNING id",
            Long::class.java, userId, stock,
        )!!
        val wrOrder = order(userId, stock, "WR:$ruleId:${System.nanoTime()}")
        jdbcTemplate.update(
            "INSERT INTO watch_rule_executions (watch_rule_id, user_id, stock_event_id, status, order_id) VALUES (?, ?, ?, 'EXECUTED', ?)",
            ruleId, userId, System.nanoTime(), wrOrder,
        )
        // 2) 모의 조건부 주문이 발동해 낸 주문
        val pcoOrder = order(userId, stock, "PCO:${System.nanoTime()}")
        val condId = jdbcTemplate.queryForObject(
            """INSERT INTO paper_conditional_orders (user_id, stock_id, side, trigger_type, trigger_price, quantity, status, executed_order_id)
               VALUES (?, ?, 'SELL', 'STOP_LOSS', 900, 1, 'EXECUTED', ?) RETURNING id""",
            Long::class.java, userId, stock, pcoOrder,
        )!!
        // 3) 키 없는 주문 = 화면 주문
        val manualOrder = order(userId, stock, null)
        // 4) 위조 가능했던 키(링크 없음) — 출처를 단정하지 않는다
        val forgedOrder = order(userId, stock, "WR:$ruleId:forged-${System.nanoTime()}")

        val wrTrade = tradeFor(wrOrder, userId, stock)
        val pcoTrade = tradeFor(pcoOrder, userId, stock)
        val manualTrade = tradeFor(manualOrder, userId, stock)
        val forgedTrade = tradeFor(forgedOrder, userId, stock)
        // 5) ADR-047 이전 구 페이퍼 경로(fill_id 없음)
        val legacyTrade = jdbcTemplate.queryForObject(
            "INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount) VALUES (?, ?, 'BUY', 1, 1000, 1000) RETURNING id",
            Long::class.java, userId, stock,
        )!!

        runBackfill()

        assertThat(originOf("orders", wrOrder)).isEqualTo("WATCH_RULE" to ruleId)
        assertThat(originOf("orders", pcoOrder)).isEqualTo("CONDITIONAL" to condId)
        assertThat(originOf("orders", manualOrder)).isEqualTo("MANUAL" to null)
        assertThat(originOf("orders", forgedOrder)).isEqualTo(null to null)
        assertThat(originOf("paper_trades", wrTrade)).isEqualTo("WATCH_RULE" to ruleId)
        assertThat(originOf("paper_trades", pcoTrade)).isEqualTo("CONDITIONAL" to condId)
        assertThat(originOf("paper_trades", manualTrade)).isEqualTo("MANUAL" to null)
        assertThat(originOf("paper_trades", forgedTrade)).isEqualTo(null to null)
        assertThat(originOf("paper_trades", legacyTrade)).isEqualTo("MANUAL" to null)

        // 재실행해도 같다(이미 채운 행은 건드리지 않는다)
        runBackfill()
        assertThat(originOf("paper_trades", wrTrade)).isEqualTo("WATCH_RULE" to ruleId)
        assertThat(originOf("orders", forgedOrder)).isEqualTo(null to null)
    }

    @Test
    fun `backfill moves OTHER tags whose memo is exactly the planned label to PLANNED`() {
        val userId = newUser()
        val stock = stockId()
        val exact = tradeFor(order(userId, stock, null), userId, stock)
        val padded = tradeFor(order(userId, stock, null), userId, stock)
        val extra = tradeFor(order(userId, stock, null), userId, stock)
        val tag = { tradeId: Long, emotion: String, memo: String? ->
            jdbcTemplate.update("INSERT INTO order_emotion_tags (paper_trade_id, user_id, emotion, memo) VALUES (?, ?, ?, ?)", tradeId, userId, emotion, memo)
        }
        tag(exact, "OTHER", "계획대로")
        tag(padded, "OTHER", " 계획대로 ")
        tag(extra, "OTHER", "계획대로 했지만 늦었다")

        runBackfill()

        val rows = jdbcTemplate.query(
            "SELECT paper_trade_id, emotion, memo FROM order_emotion_tags WHERE user_id = ?",
            { rs, _ -> rs.getLong(1) to (rs.getString(2) to rs.getString(3)) }, userId,
        ).toMap()
        assertThat(rows[exact]).isEqualTo("PLANNED" to null)
        assertThat(rows[padded]).isEqualTo("PLANNED" to null)
        assertThat(rows[extra]).isEqualTo("OTHER" to "계획대로 했지만 늦었다")   // 사용자 메모는 추측해 바꾸지 않는다
    }

    @Test
    fun `origin columns reject unknown values`() {
        val userId = newUser()
        assertThatThrownBy {
            jdbcTemplate.update(
                "INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, origin) VALUES (?, ?, 'BUY', 1, 1, 1, 'CLIENT')",
                userId, stockId(),
            )
        }.hasMessageContaining("ck_paper_trades_origin")
    }

    // ── 출처별 실현 손익 ──────────────────────────────────────────────────────────

    private fun trade(userId: Long, stockId: Long, side: String, qty: Int, amount: String, at: Long, origin: String, ref: Long? = null) =
        jdbcTemplate.queryForObject(
            """INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, traded_at, origin, origin_ref)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
            Long::class.java, userId, stockId, side, qty,
            BigDecimal(amount).divide(BigDecimal(qty)), BigDecimal(amount),
            Timestamp.from(Instant.parse("2026-10-01T00:00:00Z").plusSeconds(at)), origin, ref,
        )!!

    @Test
    fun `watch rule pnl is attributed to the rule that sold, against the moving average cost`() {
        val userId = newUser()
        val a = stockId(0)
        val b = stockId(1)
        trade(userId, a, "BUY", 10, "10000", 1, "MANUAL")            // 10 @1,000
        trade(userId, a, "BUY", 10, "20000", 2, "WATCH_RULE", 7)     // 10 @2,000 → 평균 1,500
        val sellA = trade(userId, a, "SELL", 5, "9000", 3, "WATCH_RULE", 7)   // 5 @1,800 → +1,500
        val sellB = trade(userId, a, "SELL", 5, "5000", 4, "WATCH_RULE", 8)   // 5 @1,000 → −2,500
        trade(userId, a, "SELL", 10, "30000", 5, "MANUAL")           // 직접 매도 — 규칙 손익에 넣지 않는다
        trade(userId, b, "BUY", 1, "500", 6, "WATCH_RULE", 8)        // 매수만 — 손익 0, 체결 수에는 든다
        // 다른 사용자의 같은 규칙 id 거래는 섞이지 않는다
        val other = newUser()
        trade(other, a, "BUY", 1, "100", 1, "WATCH_RULE", 7)
        trade(other, a, "SELL", 1, "900", 2, "WATCH_RULE", 7)

        val service = PaperRealizedPnlService(jdbcTemplate)
        val r = service.byOrigin(userId, OrderOriginType.WATCH_RULE)

        assertThat(r.totalRealizedPnl).isEqualByComparingTo("-1000")
        assertThat(r.sellCount).isEqualTo(2)
        assertThat(r.tradeCount).isEqualTo(4)
        val byRef = r.byRef.associateBy { it.originRef }
        assertThat(byRef[7L]!!.realizedPnl).isEqualByComparingTo("1500")
        assertThat(byRef[7L]!!.tradeCount).isEqualTo(2)
        assertThat(byRef[8L]!!.realizedPnl).isEqualByComparingTo("-2500")
        assertThat(byRef[8L]!!.sellCount).isEqualTo(1)
        assertThat(byRef[8L]!!.tradeCount).isEqualTo(2)

        val sells = service.forSells(userId, listOf(sellA, sellB))
        assertThat(sells[sellA]!!.pnl).isEqualByComparingTo("1500")
        assertThat(sells[sellB]!!.pnl).isEqualByComparingTo("-2500")
        assertThat(service.forSells(other, listOf(sellA))).isEmpty()   // 남의 거래 id는 계산하지 않는다
    }

    @Test
    fun `an origin with no trades returns zero totals`() {
        val r = PaperRealizedPnlService(jdbcTemplate).byOrigin(newUser(), OrderOriginType.CONDITIONAL)

        assertThat(r.totalRealizedPnl).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(r.sellCount).isZero()
        assertThat(r.byRef).isEmpty()
    }
}
