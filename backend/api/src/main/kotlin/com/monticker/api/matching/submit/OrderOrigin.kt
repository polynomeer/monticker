package com.monticker.api.matching.submit

/**
 * ADR-085 — 모의 주문의 진입 출처. 주문을 내는 **서버 코드 경로**가 정한다 — 클라이언트 요청 본문에서 받지 않는다.
 *
 * - [MANUAL]: 사용자가 화면에서 직접 낸 주문(paper 파사드, `/api/matching/orders`). ref 없음.
 * - [WATCH_RULE]: Watch Rule 발동(ADR-051·077). ref = watch_rules.id
 * - [CONDITIONAL]: 모의 조건부 주문 발동(ADR-075). ref = paper_conditional_orders.id
 * - [STRATEGY]: 퀀트 전략이 직접 낸 모의 주문. ref = 룰셋 id. **아직 이 값을 쓰는 경로는 없다**(전략 신호 발동은
 *   Watch Rule을 거치므로 WATCH_RULE이다). 경로가 생길 때 스키마 변경 없이 쓰도록 자리만 둔다.
 *
 * DB 값은 enum 이름 그대로다(orders.origin·paper_trades.origin, V81 CHECK).
 */
enum class OrderOriginType { MANUAL, WATCH_RULE, CONDITIONAL, STRATEGY }

data class OrderOrigin(val type: OrderOriginType, val ref: Long? = null) {
    init {
        if (type == OrderOriginType.MANUAL) require(ref == null) { "MANUAL 주문은 출처 ref가 없습니다" }
        else require(ref != null && ref > 0) { "$type 주문에는 출처 ref(양수)가 필요합니다" }
    }

    companion object {
        val MANUAL = OrderOrigin(OrderOriginType.MANUAL)
        fun watchRule(ruleId: Long) = OrderOrigin(OrderOriginType.WATCH_RULE, ruleId)
        fun conditional(conditionalOrderId: Long) = OrderOrigin(OrderOriginType.CONDITIONAL, conditionalOrderId)
        fun strategy(ruleSetId: Long) = OrderOrigin(OrderOriginType.STRATEGY, ruleSetId)
    }
}
