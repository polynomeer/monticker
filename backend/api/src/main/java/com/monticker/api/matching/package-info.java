/**
 * 주문 체결 모듈.
 * 다른 모듈과의 통신은 OrderFilledEvent, OrderCancelledEvent를 통한 이벤트 방식만 허용한다 —
 * 단, risk 모듈은 예외다(ADR-025). 리스크 한도 판정은 matching(페이퍼)과 brokerage(실거래)
 * 양쪽이 공유하는 별도 모듈로 분리했고, matching.api.RiskController가 그 설정/조회 화면을
 * 그대로 담당하므로 risk::api를 직접 참조한다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "risk::api"}
)
package com.monticker.api.matching;
