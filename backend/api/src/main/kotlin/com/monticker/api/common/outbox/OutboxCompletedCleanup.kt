package com.monticker.api.common.outbox

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.CompletedEventPublications
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * ADR-094 — 완료된 Outbox 발행 기록(completion_date IS NOT NULL) 정리.
 *
 * Modulith 기본 완료 모드(UPDATE)는 행을 지우지 않아 event_publication이 끝없이 자랐다(로컬에서 api ~13k,
 * worker ~50k). 완료 행을 읽는 코드는 없다 — 재전송·게이지·런북 모두 미완료 행만 본다. 그래도 즉시 지우지 않고
 * [RETENTION]만큼 남기는 이유는 중복 발행·유실 조사 때 "언제 기록돼 언제 외부화됐나"를 보는 유일한 흔적이기 때문이다.
 * 7일은 주말을 낀 사고를 다음 주 초에 조사해도 흔적이 남는 길이다.
 *
 * JPQL 일괄 삭제(`completionDate < ?`)라 행을 엔티티로 읽지 않는다 — 이벤트 클래스를 해석하지 않으므로, 전환 기간에
 * 이 테이블에 남은 구버전 worker의 완료 행도 함께 지운다(최초 1회는 그 누적분까지라 무겁다 — completion_date 인덱스를
 * 탄다). 여러 인스턴스가 동시에 돌아도 멱등이다.
 */
@Component
class OutboxCompletedCleanup(
    private val completedPublications: CompletedEventPublications,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 1시간마다 — 평시 한 번에 지우는 양이 한 시간치라 30s statement_timeout에 한참 못 미친다
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    fun purge() {
        log.debug("[Outbox] {}보다 오래된 완료 발행 기록 삭제", RETENTION)
        completedPublications.deletePublicationsOlderThan(RETENTION)
    }

    companion object {
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
