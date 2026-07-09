package com.monticker.api.common.outbox

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Outbox 미완료 이벤트 재전송 스케줄러.
 *
 * 앱 크래시, Kafka 일시 장애, GC pause 등으로 커밋 후 Kafka 발행이 누락된 이벤트를
 * 5분마다 재시도한다.
 *
 * event_publication 테이블에서 completion_date IS NULL인 행이 대상이다.
 * 1분 이상 미완료된 이벤트만 재전송해 정상 처리 중인 이벤트와 구분한다.
 */
@Component
class OutboxResubmissionConfig(
    private val incompletePublications: IncompleteEventPublications,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    fun resubmit() {
        log.debug("[Outbox] 미완료 이벤트 재전송 시작")
        incompletePublications.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(1))
    }
}
