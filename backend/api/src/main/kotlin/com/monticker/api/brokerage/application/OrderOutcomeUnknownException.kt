package com.monticker.api.brokerage.application

import com.monticker.api.common.exception.BusinessRuleException

/**
 * ADR-056 — 주문 의도는 커밋됐는데(그래서 증권사 호출이 나갔을 수 있다) 결과를 기록하지 못했다.
 *
 * 이 예외를 받은 호출자는 "주문이 안 나갔다"고 가정하면 안 된다. 주문 행([orderId])은 PENDING_SUBMIT으로 남고
 * 대조 잡이 해소한다. 반대로 이 타입이 아닌 예외는 의도 커밋 전에 난 것이라 증권사 호출이 없었음이 보장된다.
 */
class OrderOutcomeUnknownException(val orderId: Long, cause: Throwable) :
    BusinessRuleException("주문 결과를 확인하고 있습니다(주문 #$orderId). 잠시 후 주문 내역에서 확인해주세요.") {
    init { initCause(cause) }
}
