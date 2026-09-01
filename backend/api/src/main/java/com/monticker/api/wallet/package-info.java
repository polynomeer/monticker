/**
 * 투자 지갑 모듈 — 원장, 감정 태그, 행동 점수.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "matching::api", "paper::api", "paper::events"}
)
package com.monticker.api.wallet;
