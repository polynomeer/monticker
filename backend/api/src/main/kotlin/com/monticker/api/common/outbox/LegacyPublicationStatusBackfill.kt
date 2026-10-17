package com.monticker.api.common.outbox

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-104 — Spring Modulith 1.4(Boot 3)가 쓴 미완료 발행 행에 2.x 상태를 채운다. worker의 같은 이름 클래스와 같은 규칙이다.
 *
 * 2.x의 재전송은 1분이 지난 미완료 행을 고른 뒤 `… WHERE id = ? AND status != RESUBMITTED`로 행을 가져가는데,
 * **status가 NULL인 행은 가져가지 못하고 조용히 건너뛴다**(SQL에서 `NULL != x`는 참이 아니다). 업그레이드 직전에 쌓인
 * 행과 롤링 배포 중 구버전 pod가 계속 쓰는 행이 영원히 재전송되지 않는다. 재전송 직전마다 그런 행을 FAILED로 표시한다.
 * `completion_attempts`도 0으로 채운다 — JPA 엔티티의 primitive int라 NULL 행 하나가 조회 전체를 깨뜨린다(V102가
 * 기본값을 두지만, V102 이전에 이 컬럼 없이 들어온 행이 남아 있을 수 있다).
 *
 * 구버전이 모두 내려간 뒤에는 항상 0건이다 — 그 뒤 한 릴리스가 지나면 지운다(ADR-104 Revisit When).
 */
@Component
class LegacyPublicationStatusBackfill(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return 상태를 채운 행 수 */
    fun backfill(): Int {
        jdbc.update("UPDATE event_publication SET completion_attempts = 0 WHERE completion_attempts IS NULL")
        val updated = jdbc.update(
            """
            UPDATE event_publication
               SET status = 'FAILED'
             WHERE status IS NULL
               AND completion_date IS NULL
               AND publication_date < now() - INTERVAL '1 minute'
            """.trimIndent(),
        )
        if (updated > 0) log.info("[Outbox] Modulith 1.x가 쓴 미완료 발행 {}건을 재전송 대상(FAILED)으로 표시했다", updated)
        return updated
    }
}
