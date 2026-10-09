package com.monticker.api.paper

import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.ConnectionCallback
import java.sql.Connection

/**
 * V98~V100 — paper_trades 인덱스 정리의 근거. 주요 paper_trades 쿼리의 실행 계획을 세 상태에서 비교한다.
 *
 *  - BEFORE : V98 이전(V98 상태에서 새 인덱스를 지운 상태 — V11·V25 인덱스만)
 *  - V98    : V98 적용, 중복 인덱스 idx_paper_trades_stock·idx_paper_trades_user가 아직 남은 상태(트랜잭션 안에서 다시 만든다)
 *  - CURRENT: V99·V100까지 적용(현재 마이그레이션 결과 그대로 — 중복 인덱스 없음)
 *
 * 모든 변경(데이터·인덱스 CREATE·DROP·ANALYZE)은 한 연결의 한 트랜잭션 안에서 하고 끝에 롤백한다 — 공유 컨테이너의 다른 테스트에 남지 않는다.
 * 쿼리는 각 서비스의 SQL 모양을 그대로 옮기되 바인드 대신 리터럴을 쓴다(EXPLAIN 전용 — Instant 바인딩 문제도 피한다).
 */
class PaperTradesIndexPlanIntegrationTest : PostgresIntegrationTest() {

    private data class Plan(val text: String) {
        val totalCost: Double = Regex("""cost=[\d.]+\.\.([\d.]+)""").find(text)!!.groupValues[1].toDouble()
        /** 최상위 노드의 공유 버퍼(hit + read) — 하위 노드 합계를 포함한다. */
        val buffers: Long = Regex("""Buffers: shared( hit=(\d+))?( read=(\d+))?""").find(text)!!.let { m ->
            (m.groupValues[2].toLongOrNull() ?: 0) + (m.groupValues[4].toLongOrNull() ?: 0)
        }
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
    fun `main paper_trades queries keep or improve their plans with V98 and after dropping the redundant indexes`() {
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
            // PaperPortfolioQueryService.latestEntryOrigins — 종목별 LATERAL … LIMIT 1
            "latestEntryOrigins" to """SELECT s.stock_id, t.origin, t.origin_ref FROM unnest(ARRAY[$inList]::bigint[]) AS s(stock_id)
                CROSS JOIN LATERAL (SELECT origin, origin_ref FROM paper_trades
                    WHERE user_id = $me AND stock_id = s.stock_id AND side = 'BUY' ORDER BY traded_at DESC, id DESC LIMIT 1) t""",
            // 같은 메서드의 예전 모양(DISTINCT ON + IN) — 비교 기준으로만 남긴다
            "latestEntryOrigins.distinctOn" to """SELECT DISTINCT ON (stock_id) stock_id, origin, origin_ref FROM paper_trades
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

        // V99·V100 — 중복 인덱스는 마이그레이션이 지웠고, V25·V98 인덱스는 남아 있다
        val indexes = c.createStatement().use { st ->
            st.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'paper_trades'").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
            }
        }
        assertThat(indexes).doesNotContain("idx_paper_trades_user", "idx_paper_trades_stock")
            .contains("idx_paper_trades_user_traded", "idx_paper_trades_user_stock_traded")

        val current = plans()
        c.exec("CREATE INDEX idx_paper_trades_user  ON paper_trades (user_id, traded_at DESC)")
        c.exec("CREATE INDEX idx_paper_trades_stock ON paper_trades (user_id, stock_id)")
        // 다시 ANALYZE하지 않는다 — 표본 추출이 무작위라 상태마다 행 추정이 달라져 비용 비교가 흔들린다(단순 컬럼 인덱스는 통계가 필요 없다)
        val v98 = plans()
        c.exec("DROP INDEX idx_paper_trades_user_stock_traded")
        val before = plans()

        queries.keys.forEach { k ->
            println("=== $k ===\n--- BEFORE (V97)\n${before[k]!!.text}\n--- V98 (redundant indexes kept)\n${v98[k]!!.text}\n--- CURRENT (V100)\n${current[k]!!.text}\n")
        }

        // V98 — 종목 필터 내역은 새 인덱스로 정렬 없이 읽는다(이전엔 사용자 전체 구간을 훑었다)
        listOf("history.stock+range", "history.stock").forEach { k ->
            assertThat(current[k]!!.text).contains("idx_paper_trades_user_stock_traded")
            assertThat(current[k]!!.totalCost).isLessThan(before[k]!!.totalCost)
        }
        // 종목별 최근 매수 출처 — 종목마다 V98 인덱스를 짚고 한 행에서 멈춘다(SkipScan이 IN을 필터로 돌리던 모양이 아니다)
        current["latestEntryOrigins"]!!.text.let { plan ->
            assertThat(plan).contains("idx_paper_trades_user_stock_traded").contains("Limit").doesNotContain("SkipScan")
        }
        assertThat(current["latestEntryOrigins"]!!.buffers).isLessThan(current["latestEntryOrigins.distinctOn"]!!.buffers)
        // 중복 인덱스(V99·V100)를 지운 지금도 어떤 쿼리도 Seq Scan으로 떨어지거나 비용이 눈에 띄게 늘지 않았다
        queries.keys.forEach { k ->
            if (!v98[k]!!.text.contains("Seq Scan on paper_trades")) {
                assertThat(current[k]!!.text).describedAs(k).doesNotContain("Seq Scan on paper_trades")
            }
            assertThat(current[k]!!.totalCost).describedAs(k).isLessThanOrEqualTo(v98[k]!!.totalCost * 1.10 + 1.0)
        }
    }
}
