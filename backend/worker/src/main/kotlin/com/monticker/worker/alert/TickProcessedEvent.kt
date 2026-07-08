package com.monticker.worker.alert

import java.math.BigDecimal

/**
 * 틱 처리 완료 시 발행되는 Spring Application Event.
 *
 * MarketDataCollector (internal 모드)와 TickPipelineConfig (kafka 모드) 양쪽에서
 * 동일하게 발행하여 AlertEvaluator가 ingestion 경로에 무관하게 실시간으로 반응할 수 있게 한다.
 */
data class TickProcessedEvent(
    val stockId: Long,
    val price: BigDecimal,
)
