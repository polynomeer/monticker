/**
 * ADR-051 — 이벤트 트리거 모의 자동주문(watch rule).
 *
 * worker가 아웃박스로 발행한 탐지 이벤트를 소비해, 사용자가 미리 선언한 룰에 맞으면
 * matching::submit 파사드로 모의투자 주문을 낸다. 이벤트 탐지 로직에는 손대지 않는다 —
 * 소비자를 하나 더 붙이는 방향이다.
 *
 * 실브로커 모듈(brokerage)에 의존하지 않는다. 모의투자 전용이라는 경계를 의존성으로 고정한다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "auth::api", "matching::submit"}
)
package com.monticker.api.watchrule;
