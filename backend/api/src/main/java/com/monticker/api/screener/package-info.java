// quant::api — "퀀트 시그널 발생" 필터가 QuantSignalFeedService로 ADR-035 접근 규칙을 그대로 쓴다(ADR-072).
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "stock::api", "quant::api"}
)
package com.monticker.api.screener;
