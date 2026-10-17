package com.monticker.worker.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-104 — Spring Modulith 1.4(Boot 3)가 쓴 미완료 발행 행에 2.x 상태를 채운다.
 *
 * 1.4는 `status`·`completion_attempts`를 모른다(V101이 nullable로 추가했다). 2.x의 재전송은 대상을
 * `status = FAILED or (status is null and completion_date is null)`로 고르지만, 그다음 행을 가져가는 갱신이
 * `... WHERE id = ? AND status != 'RESUBMITTED'`라 **status가 NULL인 행은 가져가지 못하고 조용히 건너뛴다**
 * (SQL에서 `NULL != x`는 참이 아니다). 업그레이드 직전에 쌓인 행과 롤링 배포 중 구버전이 계속 쓰는 행이 영원히
 * 재전송되지 않는다. 재전송 직전마다 그런 행을 FAILED로 표시해 2.x가 집게 한다 — 기준(미완료, 1분 경과)은 지금까지의
 * 재전송 정책과 같고, 시각은 DB의 now()로 잰다. `completion_attempts + 1`이 NULL로 남지 않게 0으로 채운다.
 *
 * 구버전이 모두 내려간 뒤에는 항상 0건이다 — 그 뒤 한 릴리스가 지나면 지운다(ADR-104 Revisit When).
 */
@Component
class LegacyPublicationStatusBackfill(
    private val jdbc: JdbcTemplate,
    @Value("\${spring.modulith.events.jdbc.schema}") private val schema: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return 상태를 채운 행 수 */
    fun backfill(): Int {
        val updated = jdbc.update(
            """
            UPDATE $schema.event_publication
               SET status = 'FAILED', completion_attempts = COALESCE(completion_attempts, 0)
             WHERE status IS NULL
               AND completion_date IS NULL
               AND publication_date < now() - INTERVAL '1 minute'
            """.trimIndent(),
        )
        if (updated > 0) log.info("[Outbox] Modulith 1.x가 쓴 미완료 발행 {}건을 재전송 대상(FAILED)으로 표시했다", updated)
        return updated
    }
}
