package com.monticker.api.quant.events

import java.time.Instant
import java.time.LocalDate

/**
 * ADR-077 — 포워드 테스트가 신호(quant_signals 행)를 만들었다. 같은 트랜잭션에서 발행되며, Modulith 이벤트
 * 발행 기록(event_publication)에 남아 구독자 실패·재시작에도 재전달된다. 구독자는 [signalId]로 멱등해야 한다.
 *
 * ADR-090 — 알림 이력 적재(alert 모듈)가 문구를 만들 수 있게 [price]·[evalDate]를 싣는다. 이 필드가 생기기 전에
 * 기록된 발행(event_publication)도 재전달될 수 있어 기본값 null을 둔다.
 */
data class QuantSignalEmittedEvent(
    val signalId: Long,
    val ruleSetId: String,
    val stockId: Long,
    /** BUY | SELL */
    val direction: String,
    val signalTime: Instant,
    /** 신호를 낸 날의 종가(원) */
    val price: Double? = null,
    /** 평가 기준일(KST 거래일) */
    val evalDate: LocalDate? = null,
)
