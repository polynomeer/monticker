package com.monticker.api.brokerage.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * ADR-060 — ACTIVE 조건부 주문이 걸린 종목 집합(5초마다 갱신). Mock 증권사 모드에서 조건부 주문 평가기의 사전 필터(SpEL)가 쓴다:
 * 합성 틱은 매초 모든 종목에서 오는데, 이를 전부 @Async 큐(200)에 넣으면 실거래 모드에서 막아 둔 큐 포화(9b04b76)가 되살아난다.
 * 실거래 모드에서는 실시세 틱만 통과하므로 이 집합을 보지 않는다. 새 조건부 주문은 최대 5초 뒤부터 합성 틱을 받는다(Mock 전용).
 */
@Component
class ActiveConditionalStocks(private val jdbc: JdbcTemplate) {

    @Volatile private var stockIds: Set<Long> = emptySet()

    fun contains(stockId: Long): Boolean = stockId in stockIds

    @Scheduled(fixedDelay = 5_000, initialDelay = 0)
    fun refresh() {
        stockIds = jdbc.queryForList(
            "SELECT DISTINCT stock_id FROM conditional_orders WHERE status = 'ACTIVE'", Long::class.java,
        ).toSet()
    }
}
