/**
 * 통합 포트폴리오 모듈 — 여러 출처(손 입력·CSV·증권사 API·마이데이터)의 계좌와 거래를 모아 보유를 계산한다
 * (ADR-106, docs/account-aggregation-plan.md). 주문 경로와 분리된 표시·알림·복기용이다.
 *
 * paper::api — 모의계좌 보유를 읽기 어댑터로 붙인다(PaperPortfolioQueryService, 읽기 전용).
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "paper::api"}
)
package com.monticker.api.portfolio;
