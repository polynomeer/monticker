package com.monticker.worker.toss

import com.monticker.worker.kis.KisCoverageProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

data class TossTickTarget(val stockId: Long, val symbol: String, val market: String)

/**
 * ingestion.source에 toss가 포함될 때 Toss 실시간체결가(trade:kr/trade:us)가 실제로
 * 커버하는 종목 집합의 단일 진실 소스. ADR-031 — KIS와 겹치지 않는 상보적 배분:
 *
 *   연결 A(미국) : NASDAQ/NYSE 전체(현재 51종목, 한 연결 한도 100건 이내)
 *   연결 B(국내) : KOSPI/KOSDAQ 중 KisCoverageProvider가 이미 커버하는 종목을 제외하고
 *                  최대 100건
 *
 * MockPriceGenerator는 이 provider와 KisCoverageProvider의 커버 종목 합집합을 건너뛴다.
 */
@Component
class TossCoverageProvider(
    jdbc: JdbcTemplate,
    @Value("\${ingestion.source:internal}") ingestionSource: String,
    kisCoverage: KisCoverageProvider,
) {
    companion object {
        // Toss AsyncAPI 스펙 명시: "연결당 구독 수: 100건(codes 합산)".
        const val MAX_PER_CONNECTION = 100
    }

    private val enabled = ingestionSource.contains("toss")

    val usTargets: List<TossTickTarget> =
        if (enabled)
            jdbc.query(
                """
                SELECT id, symbol, market FROM stocks
                WHERE market IN ('NASDAQ', 'NYSE') AND is_active = true
                ORDER BY id
                """.trimIndent(),
            ) { rs, _ -> TossTickTarget(rs.getLong("id"), rs.getString("symbol"), rs.getString("market")) }
                .take(MAX_PER_CONNECTION)
        else emptyList()

    val krTargets: List<TossTickTarget> =
        if (enabled) {
            val excluded = kisCoverage.coveredStockIds
            jdbc.query(
                """
                SELECT id, symbol, market FROM stocks
                WHERE market IN ('KOSPI', 'KOSDAQ') AND is_active = true
                ORDER BY id
                """.trimIndent(),
            ) { rs, _ -> TossTickTarget(rs.getLong("id"), rs.getString("symbol"), rs.getString("market")) }
                .filterNot { it.stockId in excluded }
                .take(MAX_PER_CONNECTION)
        } else emptyList()

    val allTargets: List<TossTickTarget> = usTargets + krTargets
    val coveredStockIds: Set<Long> = allTargets.map { it.stockId }.toSet()
    val bySymbol: Map<String, TossTickTarget> = allTargets.associateBy { it.symbol }
}
