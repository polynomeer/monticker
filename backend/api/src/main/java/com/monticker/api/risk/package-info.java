/**
 * ADR-025 — 리스크 한도 판정 모듈. 원래 matching(페이퍼 트레이딩) 모듈 안에 있었으나,
 * 실거래(브로커리지) 주문에도 같은 판정 로직을 적용하기 위해 별도 모듈로 분리했다.
 * matching은 "다른 모듈과의 통신은 이벤트만 허용"이라는 원칙을 지키고 있어 그 안에 계속
 * 두면 brokerage가 matching 전체에 의존하게 된다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common"}
)
package com.monticker.api.risk;
