package com.monticker.worker.kis

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

data class KisTickTarget(val stockId: Long, val symbol: String, val market: String)

/**
 * ingestion.source=kis일 때 KIS 실시간체결가(H0STCNT0)가 실제로 커버하는 종목 집합의
 * 단일 진실 소스. MockPriceGenerator가 이 집합을 참조해 정확히 이 종목들만 생성을
 * 건너뛴다 — "KOSPI/KOSDAQ 전체"가 아니라 "실제로 KIS가 구독한 종목"만 대체해야
 * 커버리지 밖 국내 종목의 시세가 조용히 멈추지 않는다.
 *
 * MAX_TICK_SYMBOLS=21은 KIS 공식 저장소가 명시한 "appkey당 최대 41건 등록 제한"을
 * KisOrderBookSubscriber(호가, 20건)와 정적으로 나눈 값이다 — ADR-030 참고.
 */
@Component
class KisCoverageProvider(
    jdbc: JdbcTemplate,
    @Value("\${ingestion.source:internal}") ingestionSource: String,
) {
    companion object {
        const val MAX_TICK_SYMBOLS = 21
    }

    // ADR-031 — ingestion.source가 "kis,toss"처럼 콤마 구분 다중값일 수 있어 정확히
    // 일치 대신 포함 여부로 판단한다. "kis" 단독 사용 시 동작은 바뀌지 않는다.
    val targets: List<KisTickTarget> =
        if (ingestionSource.contains("kis"))
            jdbc.query(
                """
                SELECT id, symbol, market FROM stocks
                WHERE market IN ('KOSPI', 'KOSDAQ') AND is_active = true
                ORDER BY id LIMIT $MAX_TICK_SYMBOLS
                """.trimIndent(),
            ) { rs, _ -> KisTickTarget(rs.getLong("id"), rs.getString("symbol"), rs.getString("market")) }
        else emptyList()

    val coveredStockIds: Set<Long> = targets.map { it.stockId }.toSet()
    val bySymbol: Map<String, KisTickTarget> = targets.associateBy { it.symbol }
}
