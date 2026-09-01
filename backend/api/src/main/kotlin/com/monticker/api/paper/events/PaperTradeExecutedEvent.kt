package com.monticker.api.paper.events

import java.math.BigDecimal

/**
 * ADR-008/011 패턴을 페이퍼 트레이딩에도 적용 — paper 모듈은 체결 사실만 이벤트로 알리고,
 * 원장(ledger) 기록은 wallet 모듈이 이 이벤트를 구독해 직접 수행한다.
 * (paper가 wallet.application.LedgerService를 직접 호출하면 wallet -> paper 조회 의존성과
 * 맞물려 모듈 순환 의존이 발생하므로, matching -> wallet과 동일한 이벤트 기반 방식을 쓴다.)
 */
data class PaperTradeExecutedEvent(
    val userId: Long,
    val tradeId: Long,
    val stockId: Long,
    val side: String,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
)
