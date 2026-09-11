package com.monticker.api.common.exception

/**
 * 외부 서비스(증권사 API 등)의 서킷브레이커가 열려 있어 호출을 시도조차 하지 않은 상태.
 *
 * CH-06 실험(resilience-plan §6.3)에서 브레이커 OPEN이 IllegalStateException → 500으로
 * 나가는 것을 확인했다. 500은 "우리 코드가 깨졌다"는 뜻이라 ApiErrorBudgetBurn/OrderPathDown
 * 알람을 오염시키고, 클라이언트는 "재시도하면 된다"와 "버그다"를 구분할 수 없다.
 * 503 + Retry-After가 맞다 — GlobalExceptionHandler가 그렇게 매핑한다.
 */
class ExternalServiceUnavailableException(
    val service: String,
    message: String,
    cause: Throwable? = null,
    /** 클라이언트에 줄 재시도 대기(초). 브레이커의 waitDurationInOpenState와 맞춘다. */
    val retryAfterSeconds: Long = 30,
) : RuntimeException(message, cause)
