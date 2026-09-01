/**
 * Quant Lab 모듈 — 룰셋, 백테스트, 전략 보호.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "backtest", "auth::api", "settlement::api", "matching::api"}
)
package com.monticker.api.quant;
