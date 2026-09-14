/**
 * ADR-047 — paper는 "계좌 기록" 모듈이다. 체결은 matching::submit(파사드)으로 제출하고,
 * matching::api(OrderFilledEvent)를 받아 paper_trades·포지션·정산을 만든다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "auth::api", "matching::submit", "matching::api"}
)
package com.monticker.api.paper;
