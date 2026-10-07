/**
 * Quant Analytics 모듈 — 포트폴리오 최적화, Kelly, 패턴 인식, 국면 탐지.
 */
@org.springframework.modulith.ApplicationModule(
    // paper::api — 포트폴리오 최적화 결과를 사용자의 모의투자 보유 비중과 비교한다(읽기 전용).
    allowedDependencies = {"common", "backtest::api", "quant::api", "paper::api"}
)
package com.monticker.api.analytics;
