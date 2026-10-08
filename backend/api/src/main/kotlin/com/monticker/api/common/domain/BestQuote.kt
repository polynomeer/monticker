package com.monticker.api.common.domain

import java.math.BigDecimal
import java.time.Instant

/**
 * ADR-091 — 주문 접수 시점의 최우선 호가. 매수 슬리피지는 [ask], 매도 슬리피지는 [bid] 기준이다.
 * 한쪽 호가가 비어 있으면 null이다(그 방향 주문은 슬리피지 집계에서 빠진다).
 */
data class BestQuote(
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    /** 호가 스냅샷 시각(공급자가 준 값) */
    val quotedAt: Instant,
    /** 공급자 이름(예: KIS_REALTIME) */
    val source: String,
)

/**
 * 종목의 실시간 최우선 호가를 돌려주는 포트. matching(모의 체결)은 marketdata에 의존할 수 없어 common에 둔다 —
 * 구현은 marketdata가 한다. **실데이터 호가만** 돌려준다: 시뮬레이션 호가(Mock·Yahoo 깊이 모사)나 오래된 스냅샷이면 null.
 * 지어낸 호가로 슬리피지를 계산하지 않기 위해서다(design-rollout-plan 원칙).
 */
fun interface BestQuoteSource {
    fun bestQuote(stockId: Long): BestQuote?
}
