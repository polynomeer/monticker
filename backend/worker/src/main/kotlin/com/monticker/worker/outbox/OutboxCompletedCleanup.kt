package com.monticker.worker.outbox

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.CompletedEventPublications
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * ADR-094 — 완료된 worker 발행 기록 정리. api의 `common.outbox.OutboxCompletedCleanup`과 같은 정책(1시간마다, 보존 7일).
 *
 * worker는 뉴스·공시 수집과 이벤트 감지마다 SearchIndexEvent를 기록해 api보다 훨씬 빨리 쌓인다(로컬에서 ~50k).
 * 완료 행을 읽는 코드는 없고, 7일은 주말을 낀 중복·유실 조사를 다음 주 초에 해도 흔적이 남는 길이다.
 * 대상은 worker_outbox.event_publication뿐이다 — public에 남은 구버전 worker의 완료 행은 api 정리가 지운다.
 */
@Component
class OutboxCompletedCleanup(
    private val completedPublications: CompletedEventPublications,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    fun purge() {
        log.debug("[Outbox] {}보다 오래된 완료 발행 기록 삭제", RETENTION)
        completedPublications.deletePublicationsOlderThan(RETENTION)
    }

    companion object {
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
