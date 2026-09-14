package com.monticker.api.common.metrics

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Elasticsearch 폴백 카운터 (resilience-plan §A6 / P1-2).
 *
 * ES 호출은 전부 catch → log.warn → DB 폴백으로 설계돼 있다. 그 자체는 옳다 — ES가 죽어도
 * 서비스는 산다. 문제는 폴백이 발생했다는 사실이 로그에만 남아, ES가 죽은 채 몇 주가 지나도
 * 아무도 모른다는 것이다. 이 카운터가 알람 SearchFallbackSustained의 근거다.
 */
@Component
class SearchMetrics(private val registry: MeterRegistry) {
    init {
        // 카운터는 첫 증가 때 생긴다 — 폴백이 한 번도 없으면 시계열이 없어 대시보드·알람이 "데이터 없음"이다. 0으로 미리 등록.
        listOf("stocks", "news_articles", "stock_events", "watchlist_items", "alert_histories")
            .forEach { registry.counter("search_fallback_total", "index", it) }
    }

    fun fallback(index: String) =
        registry.counter("search_fallback_total", "index", index).increment()
}
