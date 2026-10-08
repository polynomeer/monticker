package com.monticker.worker.outbox

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-094 — worker Outbox 테이블(worker_outbox.event_publication)의 존재 확인과 적체 게이지.
 *
 * **기동 가드**: 이 테이블은 api의 Flyway(V91)가 만든다. worker가 먼저 배포되면 이벤트를 발행하는 모든 트랜잭션이
 * "relation does not exist"로 실패한다(수집 데이터까지 롤백). 그래서 테이블이 없으면 기동을 멈춘다 — 롤링 업데이트라면
 * 구버전 pod가 계속 일하고 새 pod만 재시작을 반복하다가, api가 V91을 적용하면 스스로 올라온다.
 *
 * **게이지**: api의 BacklogGauges(outbox_pending·outbox_oldest_age_seconds)는 이제 api 테이블만 센다. 같은 이름으로
 * worker 테이블을 내보내 기존 경보(OutboxBacklog — job 필터 없음)가 worker 적체에도 울리게 한다.
 */
@Component
class WorkerOutboxSchemaGuard(
    private val jdbc: JdbcTemplate,
    @Value("\${spring.modulith.events.jdbc.schema}") private val schema: String,
    registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val pending = AtomicLong(0)
    private val oldestAge = AtomicLong(0)

    init {
        Gauge.builder("outbox_pending", pending) { it.get().toDouble() }
            .description("$schema.event_publication에서 completion_date IS NULL인 이벤트 수").register(registry)
        Gauge.builder("outbox_oldest_age_seconds", oldestAge) { it.get().toDouble() }
            .description("가장 오래된 미완료 worker Outbox 이벤트의 나이(초)").register(registry)
    }

    @PostConstruct
    fun verifyTableExists() {
        val exists = jdbc.queryForObject(
            "SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, "$schema.event_publication",
        ) ?: false
        check(exists) {
            "$schema.event_publication이 없다 — api를 먼저 배포해 Flyway V91을 적용해야 한다(ADR-094 배포 순서: api → worker)"
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    fun refresh() {
        // 관측이 서비스를 죽이면 안 된다 — 실패는 로그만 남긴다
        runCatching {
            val row = jdbc.queryForMap(
                """
                SELECT count(*)                                                         AS pending,
                       COALESCE(EXTRACT(EPOCH FROM (now() - min(publication_date))), 0) AS oldest_age
                FROM $schema.event_publication
                WHERE completion_date IS NULL
                """.trimIndent(),
            )
            pending.set((row["pending"] as Number).toLong())
            oldestAge.set((row["oldest_age"] as Number).toLong())
        }.onFailure { log.warn("[Outbox] 게이지 갱신 실패: {}", it.message) }
    }
}
