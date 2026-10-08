package com.monticker.api.common.notification

/**
 * ADR-082 — 사용자 알림의 종류. worker가 이 값으로 사용자 알림 설정(`notification_preferences`)을 적용한다.
 * [alwaysOn]은 설정과 무관하게 보낸다 — 놓치면 이중 주문·보호 상실 같은 실제 손해로 이어진다(ADR-056/065).
 * worker `NotificationCategory`와 이름이 같아야 한다(와이어는 enum 이름). 이름·[alwaysOn]·[inApp]은
 * `backend/contracts/notification-delivery-policy.json` 사례표와 같아야 한다(api·worker 테스트가 둘 다 확인한다, ADR-093).
 *
 * @property inApp 알림 이력(`alert_histories`, /alerts)에도 남는 종류
 */
enum class NotificationCategory(val alwaysOn: Boolean = false, val inApp: Boolean = false) {
    /** 결과 불명 주문의 진입·확정·수동 검토(ADR-056). */
    ORDER_OUTCOME(alwaysOn = true),
    /** 조건부 주문 발동 실패 — 보호가 사라졌다(ADR-065). */
    CONDITIONAL_ORDER(alwaysOn = true),
    /** 리스크 한도 80% 근접(ADR-070) — 하루 한 번이라 끌 수 없게 둔다. 넘으면 매수가 막히므로 미리 아는 게 중요하다. */
    RISK_WARNING(alwaysOn = true),
    /** 실거래 체결·정산 완료. */
    FILLS,
    /** 내 룰셋·구독 전략의 포워드 테스트 매수·매도 신호 — 주인·구독자 알림 이력에도 남는다(ADR-090). */
    QUANT_SIGNAL(inApp = true),
    /** 전략 마켓 소식(광고성) — MARKETING 동의도 있어야 한다(ADR-068). 발행 전에 ConsentService.isAgreed로도 확인할 것. */
    STRATEGY_MARKET,
    /**
     * 알림 규칙(목표가·RSI·이동평균·보유 하락)·관심종목 급등락 — worker 자체 경로(`AlertDispatcher`·`StockEventWriter`)만 보낸다.
     * api는 이 종류로 발행하지 않는다. 전달 채널 표시(ADR-093)에서 같은 정책을 계산하려고 둔다.
     */
    PRICE_ALERT(inApp = true),
    /** 거래량 급증 — PRICE_ALERT와 같이 worker 자체 경로 전용. */
    VOLUME_SURGE(inApp = true),
}
