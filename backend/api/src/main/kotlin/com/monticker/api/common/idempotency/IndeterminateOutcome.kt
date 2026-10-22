package com.monticker.api.common.idempotency

/**
 * ADR-110 — 요청이 외부에 부작용(증권사 주문 등)을 냈을 수 있는데 결과를 모른다는 표시.
 *
 * 이 타입의 예외로 끝난 요청은 4xx로 응답하더라도 [IdempotencyFilter]가 키를 풀지 않고 "결과 확인 필요"로 고정한다 —
 * 같은 키로 재시도해도 다시 실행되지 않는다. 예외 핸들러가 [REQUEST_ATTRIBUTE]에 이 객체를 남겨 필터에 알린다
 * (필터는 응답만 보므로 예외 타입을 직접 알 수 없다).
 */
interface IndeterminateOutcome {
    /** 결과를 확인할 주문 id. 의도 기록 전에 났다면 없다. */
    val orderId: Long?

    companion object {
        const val REQUEST_ATTRIBUTE = "com.monticker.api.idempotency.INDETERMINATE_OUTCOME"
    }
}
