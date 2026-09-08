@org.springframework.modulith.ApplicationModule(
    // risk::api — ADR-025, 실거래 주문 제출 전 사전 리스크 게이트.
    // marketdata::domain — ADR-032, ConditionalOrderEvaluator가 MarketTickReceivedEvent/
    // PriceTick을 구독해 가격 조건을 평가한다.
    allowedDependencies = {"common", "auth::api", "wallet::api", "risk::api", "marketdata::domain"}
)
package com.monticker.api.brokerage;
