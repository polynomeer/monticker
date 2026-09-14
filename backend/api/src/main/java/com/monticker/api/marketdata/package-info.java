// common(OPEN 공유 커널, ADR-019): MarketSummaryController가 RedisGuard(P0-1 fail-open)를 쓴다.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "stock::api", "stock::domain"}
)
package com.monticker.api.marketdata;
