/**
 * Quant Analytics 모듈 — 포트폴리오 최적화, Kelly, 패턴 인식, 국면 탐지.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "backtest::api", "quant::api"}
)
package com.monticker.api.analytics;
