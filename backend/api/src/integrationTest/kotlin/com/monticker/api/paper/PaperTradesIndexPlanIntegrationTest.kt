package com.monticker.api.paper

import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.ConnectionCallback
import java.sql.Connection

/**
 * V98 — paper_trades 인덱스 정리의 근거. 주요 paper_trades 쿼리의 실행 계획을 세 상태에서 비교한다.
 *
 *  - BEFORE: V98 이전(새 인덱스를 트랜잭션 안에서 지운 상태)
 *  - AFTER : V98 적용(현재 마이그레이션 결과 그대로)
 *  - PRUNED: AFTER에서 중복 후보 idx_paper_trades_stock·idx_paper_trades_user까지 지운 상태(후속 마이그레이션 시뮬레이션)
 *
 * 모든 변경(데이터·인덱스 DROP·ANALYZE)은 한 연결의 한 트랜잭션 안에서 하고 끝에 롤백한다 — 공유 컨테이너의 다른 테스트에 남지 않는다.
 * 쿼리는 각 서비스의 SQL 모양을 그대로 옮기되 바인드 대신 리터럴을 쓴다(EXPLAIN 전용 — Instant 바인딩 문제도 피한다).
 */
class PaperTradesIndexPlanIntegrationTest : PostgresIntegrationTest() {

    private data class Plan(val text: String) {
        val totalCost: Double = Regex("""cost=[\d.]+\.\.([\d.]+)""").find(text)!!.groupValues[1].toDouble()
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.explain(sql: String): Plan = createStatement().use { st ->
        st.executeQuery("EXPLAIN (ANALYZE, BUFFERS, COSTS) $sql").use { rs ->
            Plan(generateSequence { if (rs.next()) rs.getString(1) else null }.joinToString("\n"))
        }
    }

    private fun Connection.longs(sql: String): List<Long> = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getLong(1) else null }.toList() }
    }

    @Test
    fun `main paper_trades queries keep or improve their plans with V98 and without the redundant indexes`() {
        jdbcTemplate.execute(ConnectionCallback { c ->
            c.autoCommit = false
            try {
                run(c)
            } finally {
                c.rollback()
                c.autoCommit = true
            }
        })
    }

    private fun run(c: Connection) {
        // 데이터: 대상 사용자 1명(30종목 × 2년, 2만 건) + 다른 사용자 300명(각 150건) — 사용자 범위가 넓은 실제 분포를 흉내 낸다.
        val stocks = c.longs("SELECT id FROM stocks ORDER BY id LIMIT 30")
        assertThat(stocks).hasSizeGreaterThanOrEqualTo(10)
        val stockArray = "ARRAY[${stocks.joinToString(",")}]::bigint[]"
        val users = c.longs(
            """INSERT INTO users (email, nickname)
               SELECT 'plan-' || g || '-' || clock_timestamp()::text || '@test.local', 'plan'
               FROM generate_series(1, 301) g RETURNING id""",
        )
        val me = users.first()
        c.exec(
            """INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, traded_at)
               SELECT $me, ($stockArray)[1 + g % ${stocks.size}], CASE WHEN g % 3 = 0 THEN 'SELL' ELSE 'BUY' END,
                      1, 1000, 1000, TIMESTAMPTZ '2024-10-01' + (g * INTERVAL '53 minutes')
               FROM generate_series(1, 20000) g""",
        )
        c.exec(
            """INSERT INTO paper_trades (user_id, stock_id, side, quantity, price, amount, traded_at)
               SELECT u, ($stockArray)[1 + (u + g) % ${stocks.size}], CASE WHEN g % 3 = 0 THEN 'SELL' ELSE 'BUY' END,
                      1, 1000, 1000, TIMESTAMPTZ '2025-01-01' + (g * INTERVAL '1 day')
               FROM unnest(ARRAY[${users.drop(1).joinToString(",")}]::bigint[]) u, generate_series(1, 150) g""",
        )
        c.exec("ANALYZE paper_trades")

        val s = stocks[0]
        val inList = stocks.take(8).joinToString(",")
        val history = """SELECT pt.id, pt.side, pt.stock_id, pt.quantity, pt.price, pt.amount, pt.traded_at,
                                pt.origin, pt.origin_ref, o.order_type, et.emotion, et.memo
                         FROM paper_trades pt
                         LEFT JOIN fills f  ON f.id = pt.fill_id
                         LEFT JOIN orders o ON o.id = f.order_id
                         LEFT JOIN order_emotion_tags et ON et.paper_trade_id = pt.id AND et.user_id = pt.user_id
                         WHERE pt.user_id = $me"""
        val queries = linkedMapOf(
            // PaperPortfolioQueryService.getHistory — PR #177 종목·구간 필터
            "history.stock+range" to "$history AND pt.stock_id = $s AND pt.traded_at >= TIMESTAMPTZ '2025-06-01' AND pt.traded_at < TIMESTAMPTZ '2025-09-01' ORDER BY pt.traded_at DESC, pt.id DESC LIMIT 100 OFFSET 0",
            "history.stock" to "$history AND pt.stock_id = $s ORDER BY pt.traded_at DESC, pt.id DESC LIMIT 20 OFFSET 40",
            "history.all" to "$history ORDER BY pt.traded_at DESC, pt.id DESC LIMIT 20 OFFSET 0",
            // PaperPortfolioQueryService.latestEntryOrigins
            "latestEntryOrigins" to """SELECT DISTINCT ON (stock_id) stock_id, origin, origin_ref FROM paper_trades
                WHERE user_id = $me AND side = 'BUY' AND stock_id IN ($inList) ORDER BY stock_id, traded_at DESC, id DESC""",
            // PaperPortfolioQueryService.getRiskMetrics — 매수 평균가
            "avgBuyPrice" to """SELECT stock_id, AVG(price) FROM paper_trades
                WHERE user_id = $me AND side = 'BUY' AND stock_id IN ($inList) GROUP BY stock_id""",
            // PaperTradeRepository.findTop20ByUserIdOrderByTradedAtDesc
            "top20" to "SELECT * FROM paper_trades WHERE user_id = $me ORDER BY traded_at DESC LIMIT 20",
            // EmotionTagService — LATERAL "다음 매도가"
            "nextSell" to """SELECT price FROM paper_trades WHERE user_id = $me AND stock_id = $s AND side = 'SELL'
                AND traded_at > TIMESTAMPTZ '2025-03-01' ORDER BY traded_at ASC LIMIT 1""",
            // PaperRealizedPnlService.linesFor
            "pnlLines" to """SELECT id, stock_id, side, quantity, amount, traded_at FROM paper_trades
                WHERE user_id = $me AND stock_id IN ($inList) AND traded_at <= TIMESTAMPTZ '2026-01-01' ORDER BY traded_at, id""",
            // RiskController / TaxHarvesting / BehaviorScore — 사용자 보유 집계
            "holdings" to """SELECT stock_id, SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) FROM paper_trades
                WHERE user_id = $me GROUP BY stock_id HAVING SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END) > 0""",
            // DailyReturnService — 기간 체결
            "dailyRange" to """SELECT stock_id, side, quantity, amount, traded_at FROM paper_trades
                WHERE user_id = $me AND traded_at >= TIMESTAMPTZ '2025-06-01' AND traded_at < TIMESTAMPTZ '2025-06-02' ORDER BY traded_at, id""",
            // RiskLimitNearWarningJob.candidates — 전체 사용자 × 종목 집계
            "riskCandidates" to """SELECT user_id FROM paper_trades GROUP BY user_id, stock_id
                HAVING SUM(CASE WHEN side = 'BUY' THEN quantity ELSE -quantity END) > 0""",
        )

        fun plans() = queries.mapValues { (_, sql) -> c.explain(sql) }

        val after = plans()
        c.exec("SAVEPOINT pruned")
        c.exec("DROP INDEX idx_paper_trades_stock")
        c.exec("DROP INDEX idx_paper_trades_user")
        val pruned = plans()
        c.exec("ROLLBACK TO SAVEPOINT pruned")
        c.exec("DROP INDEX idx_paper_trades_user_stock_traded")
        val before = plans()

        queries.keys.forEach { k ->
            println("=== $k ===\n--- BEFORE (V97)\n${before[k]!!.text}\n--- AFTER (V98)\n${after[k]!!.text}\n--- PRUNED (V98 + drop stock/user)\n${pruned[k]!!.text}\n")
        }

        // V98 — 종목 필터 내역은 새 인덱스로 정렬 없이 읽는다(이전엔 사용자 전체 구간을 훑었다)
        listOf("history.stock+range", "history.stock").forEach { k ->
            assertThat(after[k]!!.text).contains("idx_paper_trades_user_stock_traded")
            assertThat(after[k]!!.totalCost).isLessThan(before[k]!!.totalCost)
        }
        // 중복 인덱스를 지워도 어떤 쿼리도 Seq Scan으로 떨어지거나 비용이 눈에 띄게 늘지 않는다
        queries.keys.forEach { k ->
            if (!after[k]!!.text.contains("Seq Scan on paper_trades")) {
                assertThat(pruned[k]!!.text).describedAs(k).doesNotContain("Seq Scan on paper_trades")
            }
            assertThat(pruned[k]!!.totalCost).describedAs(k).isLessThanOrEqualTo(after[k]!!.totalCost * 1.10 + 1.0)
        }
    }
}
