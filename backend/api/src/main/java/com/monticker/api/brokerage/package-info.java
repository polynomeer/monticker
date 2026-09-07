@org.springframework.modulith.ApplicationModule(
    // risk::api — ADR-025, 실거래 주문 제출 전 사전 리스크 게이트.
    allowedDependencies = {"common", "auth::api", "wallet::api", "risk::api"}
)
package com.monticker.api.brokerage;
