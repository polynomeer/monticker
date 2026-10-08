package com.monticker.worker.outbox

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Outbox 미완료 이벤트 재전송 — api의 `common.outbox.OutboxResubmissionConfig`와 같은 정책(5분, 1분 유예).
 *
 * ADR-094 — worker의 발행 기록은 `worker_outbox.event_publication`에만 있다(JDBC 레지스트리 + schema 설정).
 * 예전에는 api와 public.event_publication을 공유해, 상대 앱의 이벤트 클래스를 해석하지 못한 재전송이 양쪽 모두
 * 통째로 실패했다. 재전송 직전에 [LegacyOutboxDrain]이 전환 기간의 구버전 worker 행을 이 테이블로 옮겨 온다.
 */
@Component
class OutboxResubmissionConfig(
    private val incomplete: IncompleteEventPublications,
    private val legacyDrain: LegacyOutboxDrain,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    fun resubmit() {
        // 이관이 실패해도 자기 테이블의 재전송은 해야 한다 — 이관 대상은 다음 주기에 다시 시도된다
        runCatching { legacyDrain.drain() }
            .onFailure { log.warn("[Outbox] 구 공유 테이블 이관 실패 — 다음 주기에 재시도: {}", it.message) }
        log.debug("[Outbox] 미완료 이벤트 재전송")
        incomplete.resubmitIncompletePublicationsOlderThan(RESUBMIT_AFTER)
    }

    companion object {
        val RESUBMIT_AFTER: Duration = Duration.ofMinutes(1)
    }
}
