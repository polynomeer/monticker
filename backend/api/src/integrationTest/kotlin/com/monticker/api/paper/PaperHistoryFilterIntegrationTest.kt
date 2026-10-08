package com.monticker.api.paper

import com.monticker.api.paper.application.PaperPortfolioQueryService
import com.monticker.api.paper.application.TradeHistoryFilter
import com.monticker.api.support.PostgresIntegrationTest
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant

/**
 * GET /api/paper/history의 종목·시각 필터를 실제 Postgres에서 검증한다.
 * 차트 마커(usePaperFills)가 한 종목·차트 구간만 받아 가도록 서버에서 거른다 — 남의 거래는 어떤 조건에서도 섞이지 않는다.
 */
class PaperHistoryFilterIntegrationTest : PostgresIntegrationTest() {

    private val service by lazy { PaperPortfolioQueryService(mockk(), mockk(), jdbcTemplate) }

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "history-${System.nanoTime()}@test.local", "history",
    )!!

    private fun stockId(offset: Int): Long =
        jdbcTemplate.queryForObject("SELECT id FROM stocks ORDER BY id OFFSET ? LIMIT 1", Long::class.java, offset)!!

    private fun trade(userId: Long, stockId: Long, at: Instant, side: String = "BUY"): Long = jdbcTemplate.queryForObject(
        """INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, traded_at)
           VALUES (?, ?, ?, 1, 1000, 1000, ?) RETURNING id""",
        Long::class.java, userId, stockId, side, Timestamp.from(at),
    )!!

    private val t0: Instant = Instant.parse("2026-09-01T00:00:00Z")
    private fun day(n: Long): Instant = t0.plusSeconds(n * 86_400)

    @Test
    fun `stockId narrows to that stock only and never returns another user's trades`() {
        val me = newUser()
        val other = newUser()
        val a = stockId(0)
        val b = stockId(1)
        val mineA = listOf(trade(me, a, day(0)), trade(me, a, day(1), "SELL"), trade(me, a, day(2)))
        trade(me, b, day(1))
        trade(other, a, day(1))
        trade(other, a, day(3))

        val rows = service.getHistory(me, 0, 100, TradeHistoryFilter(stockId = a))

        assertThat(rows.map { it.id }).containsExactlyElementsOf(mineA.reversed()) // 최신순
        assertThat(rows).allMatch { it.stockId == a }

        // 남의 종목 id로 물어도 내 거래만(없으면 빈 목록)
        assertThat(service.getHistory(other, 0, 100, TradeHistoryFilter(stockId = b))).isEmpty()
    }

    @Test
    fun `from is inclusive, to is exclusive, and paging walks the filtered set`() {
        val me = newUser()
        val a = stockId(0)
        val ids = (0L until 6L).map { trade(me, a, day(it)) }
        trade(me, stockId(1), day(2))

        val ranged = service.getHistory(me, 0, 100, TradeHistoryFilter(a, from = day(1), to = day(4)))
        assertThat(ranged.map { it.id }).containsExactly(ids[3], ids[2], ids[1])

        val fromOnly = service.getHistory(me, 0, 100, TradeHistoryFilter(a, from = day(4)))
        assertThat(fromOnly.map { it.id }).containsExactly(ids[5], ids[4])

        val toOnly = service.getHistory(me, 0, 100, TradeHistoryFilter(a, to = day(1)))
        assertThat(toOnly.map { it.id }).containsExactly(ids[0])

        val p0 = service.getHistory(me, 0, 4, TradeHistoryFilter(stockId = a))
        val p1 = service.getHistory(me, 1, 4, TradeHistoryFilter(stockId = a))
        assertThat(p0.map { it.id }).containsExactly(ids[5], ids[4], ids[3], ids[2])
        assertThat(p1.map { it.id }).containsExactly(ids[1], ids[0])
    }

    @Test
    fun `no filter keeps the existing behaviour across all stocks`() {
        val me = newUser()
        val x = trade(me, stockId(0), day(0))
        val y = trade(me, stockId(1), day(1))

        assertThat(service.getHistory(me, 0, 20).map { it.id }).containsExactly(y, x)
    }

    @Test
    fun `filtered query plan uses a user-scoped paper_trades index`() {
        // 컨테이너 테이블은 작아 플래너가 Seq Scan을 고르므로 seqscan을 끄고 "어떤 인덱스를 쓸 수 있는지"를 본다(보고용 출력).
        val plan = jdbcTemplate.execute(org.springframework.jdbc.core.ConnectionCallback { c ->
            c.createStatement().use { st ->
                st.execute("SET enable_seqscan = off")
                st.executeQuery(
                    """EXPLAIN SELECT pt.id FROM paper_trades pt
                       WHERE pt.user_id = 1 AND pt.stock_id = 1 AND pt.traded_at >= TIMESTAMPTZ '2026-01-01'
                       ORDER BY pt.traded_at DESC, pt.id DESC LIMIT 100 OFFSET 0""",
                ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                    .also { st.execute("RESET enable_seqscan") }
            }
        })!!.joinToString("\n")
        println("paper history filtered plan:\n$plan")
        assertThat(plan).contains("idx_paper_trades_user_stock_traded") // V98
    }
}
