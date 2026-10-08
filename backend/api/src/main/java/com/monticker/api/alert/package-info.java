/**
 * 알림 규칙·알림 이력. ADR-090 — 퀀트 시그널을 알림 이력에 적재하려고 quant의 신호 이벤트(quant::events)와
 * 신호 접근 규칙(quant::api, StrategySignalAccess)을 쓴다. quant는 alert에 의존하지 않는다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "quant::api", "quant::events"}
)
package com.monticker.api.alert;
