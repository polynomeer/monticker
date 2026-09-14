package com.monticker.worker.search

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Outbox 미완료 이벤트 재전송 — api의 `common.outbox.OutboxResubmissionConfig`와 같은 정책(5분, 1분 유예).
 * event_publication은 api와 공유하지만 각 인스턴스는 **자기가 기록한** 리스너 id의 행만 재전송한다
 * (worker에 없는 api 리스너는 이 프로세스에서 해석되지 않아 건너뛴다). CH-05가 이 경로를 실증했다.
 */
@Component
class OutboxResubmissionConfig(private val incomplete: IncompleteEventPublications) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    fun resubmit() {
        log.debug("[Outbox] 미완료 이벤트 재전송")
        incomplete.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1))
    }
}
