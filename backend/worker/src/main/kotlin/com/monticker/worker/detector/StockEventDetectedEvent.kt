package com.monticker.worker.detector

import org.springframework.modulith.events.Externalized
import java.time.Instant

/**
 * ADR-051 — 탐지된 이벤트의 아웃박스 발행. 소비자는 backend/api의 `watchrule.application.WatchRuleConsumer`다.
 * api의 `watchrule.events.StockEventDetectedEvent`와 **JSON 형태가 같아야 한다**(ADR-042의 SearchIndexEvent와 같은 규칙).
 *
 * 기존 `market.events`(EventKafkaProducer)를 재사용하지 않는 이유가 세 가지 있다:
 *  1. `ingestion.source=kafka`에서만 발행된다 — 기본 모드(internal)에서는 룰이 영영 발동하지 않는다.
 *  2. 페이로드가 [DetectedEvent]라 `stock_events.id`가 없다 — 멱등 키로 쓸 값이 없다.
 *  3. 베스트 에포트 발행이다(큐가 차면 오래된 것부터 버린다). 돈이 움직이는 경로에는 아웃박스가 필요하다.
 *
 * 반드시 **DB 트랜잭션 안에서** 발행해야 한다 — Modulith는 커밋 후 외부화하므로 트랜잭션 밖에서 발행하면
 * event_publication에 기록되지도, 외부화되지도 않는다. 이벤트 행과 발행 기록이 같은 커밋에 묶여야
 * "INSERT는 됐는데 발행이 안 된" 상태가 생기지 않는다.
 *
 * 파티션 키 = stockId — 같은 종목의 이벤트는 순서가 보장되고, 한 종목에 몰린 룰이 한 컨슈머로 간다.
 */
@Externalized("market.event-detected::#{#this.stockId}")
data class StockEventDetectedEvent(
    /** stock_events.id — 컨슈머의 멱등 키(watch_rule_executions 유니크). */
    val eventId: Long,
    val stockId: Long,
    /** [DetectedEventType] 이름. */
    val eventType: String,
    val importanceScore: Int,
    /** epoch millis — api 쪽 역직렬화가 타입 의존 없이 되도록 Long으로 보낸다. */
    val eventTimeMillis: Long,
) {
    companion object {
        fun of(eventId: Long, event: DetectedEvent) = StockEventDetectedEvent(
            eventId = eventId,
            stockId = event.stockId,
            eventType = event.eventType.name,
            importanceScore = event.importanceScore,
            eventTimeMillis = event.eventTime.toEpochMilli(),
        )
    }

    fun eventTime(): Instant = Instant.ofEpochMilli(eventTimeMillis)
}
