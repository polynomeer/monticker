package com.monticker.api.brokerage.application

import com.monticker.api.common.exception.BusinessRuleException

/**
 * ADR-056 중복 가드 — 결과를 모르는 같은 종목·방향 주문([pendingOrderId])이 있어 새 주문을 받지 않았다. 증권사 호출 전이다.
 *
 * 화면에서 누른 주문이면 사용자가 메시지를 바로 보지만, 조건부 주문 발동이 여기 걸리면 스탑로스가 조용히 FAILED가 된다.
 * 그래서 타입을 따로 둔다 — 발동 실패 알림이 "왜 막혔는지"를 정확히 말할 수 있게(ADR-065).
 */
class UnresolvedOrderInProgressException(val symbol: String, val side: String, val pendingOrderId: Long) :
    BusinessRuleException("증권사 확인 중인 $symbol $side 주문이 있습니다(주문 #$pendingOrderId). 확인이 끝난 뒤 다시 시도해주세요.")
