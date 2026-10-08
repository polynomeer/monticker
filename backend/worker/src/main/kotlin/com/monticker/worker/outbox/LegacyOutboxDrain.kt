package com.monticker.worker.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * ADR-094 전환용 — 구 공유 테이블(public.event_publication)에 남은 **worker 소유 미완료 행**을 worker 테이블로 옮긴다.
 *
 * V91이 배포 시점의 행을 옮기지만, api가 V91을 적용한 뒤 worker가 롤아웃되기 전까지 떠 있는 구버전 worker는 계속
 * public에 쓴다. 그 행을 api 재전송이 만나면 예전처럼 재전송 전체가 실패하므로, 새 worker가 재전송 주기마다
 * V91과 **같은 규칙**(event_type이 `com.monticker.worker.` 패키지, 미완료, 1분 이상 경과)으로 옮긴다. 경과 조건은
 * 구버전 worker가 막 외부화·완료 표시하려는 행을 가로채 중복 발행하는 일을 줄인다(경과한 행은 원래도 재전송
 * 대상이라 at-least-once 의미론이 바뀌지 않는다). 시각은 DB의 now()로 잰다 — V91과 같은 시계다.
 *
 * `DELETE … RETURNING`과 `INSERT`가 한 문장이라 옮기는 도중 사라지거나 두 곳에 남는 행이 없다. 구버전 worker가 모두
 * 내려간 뒤에는 항상 0건이다 — 그 뒤 한 릴리스가 지나면 이 클래스를 지운다(ADR-094 Revisit When).
 */
@Component
class LegacyOutboxDrain(
    private val jdbc: JdbcTemplate,
    @Value("\${spring.modulith.events.jdbc.schema}") private val schema: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return 옮긴 행 수 */
    fun drain(): Int {
        val moved = jdbc.update(
            """
            WITH moved AS (
                DELETE FROM public.event_publication
                 WHERE completion_date IS NULL
                   AND event_type LIKE 'com.monticker.worker.%'
                   AND publication_date < now() - INTERVAL '1 minute'
                RETURNING id, listener_id, event_type, serialized_event, publication_date, completion_date
            )
            INSERT INTO $schema.event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date)
            SELECT id, listener_id, event_type, serialized_event, publication_date, completion_date FROM moved
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        )
        if (moved > 0) log.info("[Outbox] 구 공유 테이블에서 worker 미완료 발행 {}건을 {}로 옮겼다", moved, schema)
        return moved
    }
}
