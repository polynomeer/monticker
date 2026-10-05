package com.monticker.api.quant.events

import java.time.Instant

/**
 * ADR-077 — 포워드 테스트가 신호(quant_signals 행)를 만들었다. 같은 트랜잭션에서 발행되며, Modulith 이벤트
 * 발행 기록(event_publication)에 남아 구독자 실패·재시작에도 재전달된다. 구독자는 [signalId]로 멱등해야 한다.
 */
data class QuantSignalEmittedEvent(
    val signalId: Long,
    val ruleSetId: String,
    val stockId: Long,
    /** BUY | SELL */
    val direction: String,
    val signalTime: Instant,
)
