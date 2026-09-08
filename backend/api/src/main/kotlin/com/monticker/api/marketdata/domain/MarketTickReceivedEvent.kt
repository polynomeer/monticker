package com.monticker.api.marketdata.domain

/**
 * ADR-032 — MarketTickBroadcastConsumer(ADR-029)가 market.ticks를 받을 때마다 발행하는
 * 인프로세스 이벤트. ConditionalOrderEvaluator가 이걸 구독해 가격 조건을 평가한다 —
 * backend/worker의 AlertEvaluator가 TickProcessedEvent를 구독하는 것과 같은 모양이다.
 */
data class MarketTickReceivedEvent(val tick: PriceTick)
