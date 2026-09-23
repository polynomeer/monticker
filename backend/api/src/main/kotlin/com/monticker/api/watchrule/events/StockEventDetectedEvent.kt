package com.monticker.api.watchrule.events

/**
 * ADR-051 — worker `detector.StockEventDetectedEvent`가 `market.event-detected`로 외부화한 것과
 * **JSON 형태가 같아야 한다**. 발행자는 worker 하나, 소비자는 [com.monticker.api.watchrule.application.WatchRuleConsumer] 하나다.
 *
 * 필드를 바꿀 때는 worker 쪽을 먼저 배포해도, api 쪽을 먼저 배포해도 역직렬화가 깨지지 않게
 * 추가는 기본값과 함께, 삭제는 두 배포에 걸쳐 한다(ADR-042의 SearchIndexEvent와 같은 규칙).
 */
data class StockEventDetectedEvent(
    val eventId: Long = 0,
    val stockId: Long = 0,
    val eventType: String = "",
    val importanceScore: Int = 0,
    val eventTimeMillis: Long = 0,
)
