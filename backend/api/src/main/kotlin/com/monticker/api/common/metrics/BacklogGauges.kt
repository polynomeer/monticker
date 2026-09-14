package com.monticker.api.common.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Outbox·Saga 적체 게이지 (resilience-plan §A5, §E3 / P1-2).
 *
 * 둘 다 "자동 복구 장치가 있다"(Outbox 5분 재전송, Saga 5분 recoverIncomplete)는 점이 같고,
 * 그 장치가 실패하고 있어도 아무 신호가 없다는 점도 같다. 30초마다 테이블을 세어 게이지로 올린다.
 * DB는 api·worker가 공유하므로 어느 서비스가 썼든 여기서 전부 보인다.
 * 알람: OutboxBacklog, SagaIncomplete.
 */
@Component
class BacklogGauges(
    private val jdbc: JdbcTemplate,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val outboxPending   = AtomicLong(0)
    private val outboxOldestAge = AtomicLong(0)
    private val sagaIncomplete  = AtomicLong(0)

    init {
        Gauge.builder("outbox_pending_total", outboxPending) { it.get().toDouble() }
            .description("event_publication에서 completion_date IS NULL인 이벤트 수").register(registry)
        Gauge.builder("outbox_oldest_age_seconds", outboxOldestAge) { it.get().toDouble() }
            .description("가장 오래된 미완료 Outbox 이벤트의 나이(초)").register(registry)
        Gauge.builder("saga_incomplete_total", sagaIncomplete) { it.get().toDouble() }
            .description("order_sagas에서 STARTED/COMPENSATING 상태인 건수").register(registry)
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    fun refresh() {
        // 게이지 갱신이 실패해도 앱에 영향을 주면 안 된다 — 관측이 서비스를 죽이면 본말전도다.
        runCatching {
            val row = jdbc.queryForMap(
                """
                SELECT count(*)                                                       AS pending,
                       COALESCE(EXTRACT(EPOCH FROM (now() - min(publication_date))), 0) AS oldest_age
                FROM event_publication
                WHERE completion_date IS NULL
                """.trimIndent(),
            )
            outboxPending.set((row["pending"] as Number).toLong())
            outboxOldestAge.set((row["oldest_age"] as Number).toLong())

            sagaIncomplete.set(
                jdbc.queryForObject(
                    "SELECT count(*) FROM order_sagas WHERE status IN ('STARTED', 'COMPENSATING')",
                    Long::class.java,
                ) ?: 0L
            )
        }.onFailure { log.warn("[BacklogGauges] 갱신 실패: {}", it.message) }
    }
}
