package com.monticker.api.marketdata.application

import com.monticker.api.common.domain.BestQuote
import com.monticker.api.common.domain.BestQuoteSource
import com.monticker.api.marketdata.infrastructure.orderbook.KisOrderBookProvider
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration

/**
 * ADR-091 — 모의 주문 접수 시점의 최우선 호가. KIS 실시간 호가(worker가 Redis에 캐시, TTL 30초)만 쓴다.
 * Yahoo(현재가 기준 깊이 모사)·Mock(무작위)은 최우선 호가 자체가 지어낸 값이라 쓰지 않는다 — null을 돌려주고,
 * 그 주문은 슬리피지 집계에서 "호가 기록 없음"으로 빠진다.
 *
 * 주문 경로 안에서 불리므로 네트워크 호출이 없다(Redis GET 1번 + 종목 조회 1번). 실패하면 null — 호가 기록이 없다고
 * 주문을 막지 않는다.
 */
@Component
class KisBestQuoteSource(
    private val jdbc: JdbcTemplate,
    private val kisProvider: KisOrderBookProvider,
) : BestQuoteSource {
    private val log = LoggerFactory.getLogger(javaClass)

    internal var clock: Clock = Clock.systemUTC()

    companion object {
        /** 이보다 오래된 스냅샷은 "접수 시점 호가"로 보지 않는다(Redis TTL과 같다). */
        val MAX_AGE: Duration = Duration.ofSeconds(30)
    }

    override fun bestQuote(stockId: Long): BestQuote? = runCatching {
        val row = jdbc.query(
            "SELECT symbol, market FROM stocks WHERE id = ?",
            { rs, _ -> rs.getString("symbol") to (rs.getString("market") ?: "KOSPI") }, stockId,
        ).firstOrNull() ?: return null
        val snap = kisProvider.getOrderBook(row.first, row.second, BigDecimal.ZERO) ?: return null
        if (snap.updatedAt.isBefore(clock.instant().minus(MAX_AGE))) return null
        val bid = snap.bids.firstOrNull()?.price?.takeIf { it.signum() > 0 }
        val ask = snap.asks.firstOrNull()?.price?.takeIf { it.signum() > 0 }
        if (bid == null && ask == null) return null
        BestQuote(bid = bid, ask = ask, quotedAt = snap.updatedAt, source = snap.source.name)
    }.onFailure { log.debug("best quote lookup failed stockId={}: {}", stockId, it.message) }.getOrNull()
}
