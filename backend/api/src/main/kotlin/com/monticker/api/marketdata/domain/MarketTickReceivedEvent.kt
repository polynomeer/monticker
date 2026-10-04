package com.monticker.api.marketdata.domain

import java.time.Duration
import java.time.Instant

/**
 * ADR-032 — MarketTickBroadcastConsumer(ADR-029)가 market.ticks를 받을 때마다 발행하는
 * 인프로세스 이벤트. ConditionalOrderEvaluator가 이걸 구독해 가격 조건을 평가한다 —
 * backend/worker의 AlertEvaluator가 TickProcessedEvent를 구독하는 것과 같은 모양이다.
 *
 * ADR-055 — [provenance]는 기본값이 없다. 이 이벤트로 실제 돈이 움직이므로, 발행하는 쪽이
 * 출처를 반드시 말하게 만든다.
 */
data class MarketTickReceivedEvent(val tick: PriceTick, val provenance: TickProvenance)

/**
 * ADR-055 — market.ticks 한 건의 출처. 같은 토픽에 실시세(KIS·Toss)와 합성 시세(Mock)가
 * 종목별로 섞여 흐르므로(ADR-030/031), 실주문을 내는 소비자는 틱을 쓰기 전에 이걸 봐야 한다.
 */
enum class PriceSource {
    KIS, TOSS, MOCK,

    /** 와이어에 source가 없거나 모르는 값 — 실시세로 취급하지 않는다(fail-closed). */
    UNKNOWN;

    val isReal: Boolean get() = this == KIS || this == TOSS

    companion object {
        fun fromWire(raw: String?): PriceSource =
            entries.firstOrNull { it.name == raw && it != UNKNOWN } ?: UNKNOWN
    }
}

data class TickProvenance(
    val source: PriceSource,
    /** worker MarketSchedule 기준 세션 상태(OPEN·PRE_MARKET·POST_MARKET·CLOSED). */
    val marketStatus: String?,
    /** 생산자가 틱을 만든 시각. 파이프라인 지연을 재는 기준이다. */
    val generatedAt: Instant,
) {
    /**
     * 실주문 판단에 써도 되는 시세인가 — 실시세이고, 정규장 중이고, 파이프라인 랙 SLO(ADR-045, 5s)
     * 안에 도착했다. 셋 중 하나라도 아니면 그 틱으로는 돈을 움직이지 않는다.
     */
    fun rejectReasonForRealOrder(now: Instant, maxAge: Duration = MAX_AGE): String? = when {
        !source.isReal -> "source=$source"
        marketStatus != "OPEN" -> "marketStatus=$marketStatus"
        Duration.between(generatedAt, now) > maxAge -> "stale"
        else -> null
    }

    companion object {
        val MAX_AGE: Duration = Duration.ofSeconds(5)
    }
}
