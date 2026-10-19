package com.monticker.api.portfolio.application

import com.monticker.api.event.application.EventTimelineService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class HoldingEventView(
    val id: Long,
    val stockId: Long,
    val symbol: String?,
    val name: String?,
    val eventType: String,
    val title: String,
    val description: String?,
    val eventTime: Instant,
    val importanceScore: Int,
    val sourceType: String?,
)

data class HoldingEventFeed(
    val events: List<HoldingEventView>,
    /** 다음 페이지는 `cursor=nextCursor`. 더 없으면 null */
    val nextCursor: String?,
    /** 지금 보유 중인 종목 수 — 0이면 화면이 "계좌를 연결하세요"를 보인다 */
    val heldStockCount: Int,
)

/**
 * 보유 종목 피드(ADR-108) — 모든 실계좌에서 지금 가진 종목의 이벤트(감지·뉴스·공시)를 최신순으로.
 * 알림(worker `StockAudience`)과 같은 "보유" 기준이라, 알림을 받은 이벤트는 여기에도 있다.
 */
@Service
@Transactional(readOnly = true)
class PortfolioEventFeedService(
    private val holdings: PortfolioHoldingsQueryService,
    private val events: EventTimelineService,
    private val jdbc: JdbcTemplate,
) {
    fun feed(userId: Long, cursor: String?, limit: Int): HoldingEventFeed {
        val stockIds = holdings.heldStockIds(userId)
        if (stockIds.isEmpty()) return HoldingEventFeed(emptyList(), null, 0)

        val (beforeTime, beforeId) = cursor?.let(::parseCursor) ?: (Instant.now().plusSeconds(60) to Long.MAX_VALUE)
        // 하나 더 읽어 다음 페이지가 있는지 본다
        val page = events.getRecentForStocks(stockIds, beforeTime, beforeId, limit + 1)
        val shown = page.take(limit)
        val names = stockNames(shown.map { it.stockId }.distinct())
        return HoldingEventFeed(
            events = shown.map { e ->
                HoldingEventView(
                    id = e.id, stockId = e.stockId, symbol = names[e.stockId]?.first, name = names[e.stockId]?.second,
                    eventType = e.eventType.name, title = e.title, description = e.description, eventTime = e.eventTime,
                    importanceScore = e.importanceScore, sourceType = e.sourceType,
                )
            },
            nextCursor = if (page.size > limit) shown.last().let { "${it.eventTime}_${it.id}" } else null,
            heldStockCount = stockIds.size,
        )
    }

    /** "2026-10-19T01:02:03.456789Z_123" — 응답의 nextCursor를 그대로 돌려받는다 */
    private fun parseCursor(cursor: String): Pair<Instant, Long> {
        val at = cursor.lastIndexOf('_')
        return runCatching { Instant.parse(cursor.substring(0, at)) to cursor.substring(at + 1).toLong() }
            .getOrElse { throw IllegalArgumentException("cursor 형식이 올바르지 않습니다") }
    }

    private fun stockNames(ids: List<Long>): Map<Long, Pair<String, String>> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query(
            "SELECT id, symbol, name FROM stocks WHERE id IN (${ids.joinToString(",") { "?" }})",
            { rs, _ -> rs.getLong("id") to (rs.getString("symbol") to rs.getString("name")) },
            *ids.toTypedArray(),
        ).toMap()
    }
}
