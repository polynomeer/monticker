package com.monticker.api.paper

import com.monticker.api.paper.application.PaperPortfolioQueryService
import com.monticker.api.support.PostgresIntegrationTest
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant

/**
 * ADR-085 — 보유 종목별 "가장 최근 매수 체결의 진입 출처"(PaperPortfolioQueryService.latestEntryOrigins).
 * 쿼리를 `DISTINCT ON (stock_id) … stock_id IN (…)`에서 종목별 `unnest + LATERAL … LIMIT 1`로 바꿨다(V98 인덱스를 타게).
 * 바꾼 쿼리가 예전 쿼리와 같은 답을 내는지 실제 Postgres에서 비교한다 — 매도는 건너뛰고, 같은 시각이면 id가 큰 쪽,
 * 매수가 없는 종목은 출처 없음, 다른 사용자의 거래는 섞이지 않는다.
 */
class PaperLatestEntryOriginIntegrationTest : PostgresIntegrationTest() {

    private val service by lazy { PaperPortfolioQueryService(mockk(), mockk(), jdbcTemplate) }

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "latest-origin-${System.nanoTime()}@test.local", "latest-origin",
    )!!

    private fun stockId(offset: Int): Long =
        jdbcTemplate.queryForObject("SELECT id FROM stocks ORDER BY id OFFSET ? LIMIT 1", Long::class.java, offset)!!

    private val t0 = Instant.parse("2026-10-01T00:00:00Z")

    private fun trade(userId: Long, stockId: Long, side: String, at: Long, origin: String?, ref: Long? = null): Long =
        jdbcTemplate.queryForObject(
            """INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, traded_at, origin, origin_ref)
               VALUES (?, ?, ?, 1, 1000, 1000, ?, ?, ?) RETURNING id""",
            Long::class.java, userId, stockId, side, Timestamp.from(t0.plusSeconds(at)), origin, ref,
        )!!

    private fun holdingWithPrice(userId: Long, stockId: Long) {
        jdbcTemplate.update(
            "INSERT INTO portfolio_positions (user_id, stock_id, net_qty, avg_buy_price, total_cost) VALUES (?, ?, 1, 1000, 1000)",
            userId, stockId,
        )
        jdbcTemplate.update(
            """INSERT INTO candles_1m (stock_id, open, high, low, close, volume, candle_time)
               VALUES (?, 1000, 1000, 1000, 1000, 1, ?) ON CONFLICT DO NOTHING""",
            stockId, Timestamp.from(t0),
        )
    }

    /** 바꾸기 전 쿼리 그대로 — 비교 기준. */
    private fun oldQuery(userId: Long, stockIds: List<Long>): Map<Long, Pair<String?, Long?>> = jdbcTemplate.query(
        """SELECT DISTINCT ON (stock_id) stock_id, origin, origin_ref
           FROM paper_trades
           WHERE user_id = ? AND side = 'BUY' AND stock_id IN (${stockIds.joinToString(",") { "?" }})
           ORDER BY stock_id, traded_at DESC, id DESC""",
        { rs, _ -> rs.getLong("stock_id") to (rs.getString("origin") to (rs.getObject("origin_ref") as Number?)?.toLong()) },
        userId, *stockIds.toTypedArray(),
    ).toMap()

    @Test
    fun `latest buy origin per held stock matches the previous DISTINCT ON semantics`() {
        val me = newUser()
        val other = newUser()
        val (a, b, c, d, e) = (0 until 5).map { stockId(it) }

        // a — 더 늦은 매수가 이긴다. 그 뒤의 매도는 보지 않는다.
        trade(me, a, "BUY", 10, "MANUAL")
        trade(me, a, "BUY", 20, "WATCH_RULE", 7)
        trade(me, a, "SELL", 30, "STRATEGY", 99)
        // b — 같은 시각 매수 둘: id가 큰 쪽(나중에 들어온 행)
        trade(me, b, "BUY", 50, "CONDITIONAL", 3)
        trade(me, b, "BUY", 50, "STRATEGY", 9)
        trade(me, b, "BUY", 40, "MANUAL")
        // c — 매도만 있다(매수 이력 없음) → 출처 없음
        trade(me, c, "SELL", 10, "MANUAL")
        // d — 판정 불가(origin NULL) 매수가 최신
        trade(me, d, "BUY", 10, "WATCH_RULE", 1)
        trade(me, d, "BUY", 20, null)
        // e — 내 거래는 없고 다른 사용자의 더 늦은 매수만 있다 → 섞이지 않는다
        trade(other, e, "BUY", 100, "STRATEGY", 5)
        trade(other, a, "BUY", 100, "CONDITIONAL", 4)

        val stocks = listOf(a, b, c, d, e)
        stocks.forEach { holdingWithPrice(me, it) }

        val holdings = service.buildHoldings(me).associateBy { it.stockId }
        assertThat(holdings.keys).containsExactlyInAnyOrderElementsOf(stocks)
        val got = holdings.mapValues { (_, h) -> h.entryOrigin to h.entryOriginRef }

        assertThat(got[a]).isEqualTo("WATCH_RULE" to 7L)
        assertThat(got[b]).isEqualTo("STRATEGY" to 9L)
        assertThat(got[c]).isEqualTo(null to null)
        assertThat(got[d]).isEqualTo(null to null)
        assertThat(got[e]).isEqualTo(null to null)

        // 예전 쿼리와 종목마다 같은 답(예전 쿼리에 없는 종목 = 출처 없음)
        val old = oldQuery(me, stocks)
        assertThat(old.keys).containsExactlyInAnyOrder(a, b, d)
        stocks.forEach { s -> assertThat(got[s]).describedAs("stock $s").isEqualTo(old[s] ?: (null to null)) }
    }
}
