/**
 * ADR-077 — 다른 모듈이 구독하는 quant 모듈의 이벤트 계약(QuantSignalEmittedEvent).
 * watchrule이 "전략 신호로 모의 주문" 규칙을 발동하는 데 쓴다.
 */
@org.springframework.modulith.NamedInterface("events")
package com.monticker.api.quant.events;
