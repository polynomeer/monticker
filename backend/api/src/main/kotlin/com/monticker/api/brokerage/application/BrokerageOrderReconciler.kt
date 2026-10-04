package com.monticker.api.brokerage.application

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-056 — 결과 불명(UNKNOWN)·제출 중 중단(PENDING_SUBMIT) 실거래 주문을 주기적으로 증권사와 대조한다.
 *
 * 행 하나의 대조는 [BrokerageService.reconcileUnresolved]가 SKIP LOCKED로 처리하므로 api 레플리카마다 이 잡이
 * 돌아도 같은 행을 두 번 처리하지 않는다. 재주문은 하지 않는다.
 */
@Component
class BrokerageOrderReconciler(
    private val jdbc: JdbcTemplate,
    private val brokerageService: BrokerageService,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val unresolved = AtomicLong(0)

    init {
        // 5분 넘게 해소되지 않은 주문 수 — 0이 아니면 실제 돈의 상태를 모른다는 뜻이다(alert-rules.yml Page).
        Gauge.builder("brokerage_order_unresolved", unresolved) { it.get().toDouble() }
            .description("5분 넘게 결과가 확인되지 않은 실거래 주문 수 (ADR-056)")
            .register(meterRegistry)
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    fun reconcileDue() {
        val ids = jdbc.queryForList(
            """
            SELECT id FROM brokerage_orders
            WHERE status IN ('PENDING_SUBMIT','UNKNOWN') AND submitted_at < now() - interval '30 seconds'
              AND (next_reconcile_at IS NULL OR next_reconcile_at <= now())
            ORDER BY next_reconcile_at NULLS FIRST, submitted_at
            LIMIT $BATCH
            """.trimIndent(),
            Long::class.java,
        )
        ids.forEach { id ->
            runCatching { brokerageService.reconcileUnresolved(id) }
                .onFailure { log.warn("[BrokerageOrderReconciler] 대조 실패: orderId={} reason={}", id, it.message) }
        }
        unresolved.set(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM brokerage_orders WHERE status IN ('PENDING_SUBMIT','UNKNOWN') AND submitted_at < now() - interval '5 minutes'",
                Long::class.java,
            ) ?: 0L,
        )
    }

    companion object {
        /** 한 주기 처리 상한. 증권사 조회(≤5s)를 행 락 트랜잭션 안에서 하므로 작게 둔다. */
        const val BATCH = 20
    }
}
