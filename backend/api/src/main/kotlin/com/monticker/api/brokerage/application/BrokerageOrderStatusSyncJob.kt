package com.monticker.api.brokerage.application

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * ADR-061 — 접수된(SUBMITTED) 실거래 주문의 상태를 60초마다 증권사에 물어 반영한다. 지정가가 장중에 체결돼도 정산 기록과 원장이
 * 사용자가 확인할 때까지 생기지 않던 문제를 닫는다.
 *
 * 최근 24시간 접수분만 본다(당일 유효 주문 — ADR-058과 같은 창). 마지막 확인이 오래된 순으로 최대 [BATCH]건 — 미체결이 많아도
 * 돌아가며 모두 확인된다. 행 하나의 처리는 [BrokerageService.syncSubmittedOrder]가 SKIP LOCKED로 한다.
 */
@Component
class BrokerageOrderStatusSyncJob(
    private val jdbc: JdbcTemplate,
    private val brokerageService: BrokerageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    fun syncDue() {
        val ids = jdbc.queryForList(
            """
            SELECT id FROM brokerage_orders
            WHERE status = 'SUBMITTED' AND pg_order_id IS NOT NULL AND submitted_at > now() - interval '24 hours'
            ORDER BY status_synced_at NULLS FIRST, submitted_at
            LIMIT $BATCH
            """.trimIndent(),
            Long::class.java,
        )
        ids.forEach { id ->
            runCatching { brokerageService.syncSubmittedOrder(id) }
                .onFailure { log.warn("[BrokerageOrderStatusSync] 동기화 실패: orderId={} reason={}", id, it.message) }
        }
    }

    companion object {
        /** 한 주기 처리 상한 — 증권사 호출 수(사용자 앱키 레이트리밋)를 묶는다. */
        const val BATCH = 50
    }
}
