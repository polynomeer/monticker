// common(OPEN 공유 커널, ADR-019): ES 폴백 카운터 SearchMetrics(P1-2)를 쓴다.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common"}
)
package com.monticker.api.stock;
