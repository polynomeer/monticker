package com.monticker.api.common.exception

/**
 * ADR-057 — 실거래 주문 킬 스위치에 막혔다. GlobalExceptionHandler가 **423 Locked**로 매핑한다.
 *
 * 5xx가 아닌 이유: 의도적인 정지가 OrderPathDown(주문 경로 5xx 비율)·ApiErrorBudgetBurn Page를 울리면 정지 중에 일어난
 * 진짜 장애를 놓친다. [scope]는 GLOBAL/PROVIDER/USER — 클라이언트가 배너 문구를 고르는 데 쓴다.
 */
class TradingHaltedException(message: String, val scope: String) : RuntimeException(message)
