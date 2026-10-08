// common(OPEN 공유 커널, ADR-019): ES 폴백 카운터 SearchMetrics(P1-2)를 쓴다.
// marketdata::api/domain: 관심종목 응답의 52주 최고/최저(CandleService.get52WeekRanges)를 쓴다.
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "stock::api", "stock::domain", "marketdata::api", "marketdata::domain"}
)
package com.monticker.api.watchlist;
