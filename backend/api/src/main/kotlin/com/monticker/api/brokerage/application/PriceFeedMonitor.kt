package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.brokerage.infrastructure.BrokerageClientRegistry
import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** ADR-060 — 조건부 주문 종목의 실시세 상태. */
enum class PriceFeed {
    /** 실시세가 연결돼 있다(장 마감 중이라 틱이 없는 것도 포함). */
    LIVE,
    /** 커버리지에는 있는데 장중에 이 종목만 실시세가 끊겼다. */
    STALE,
    /** 커버리지에 없다(공표 없음·만료) — 이 종목의 조건부 주문은 발동하지 않는다. */
    NONE,
}

/**
 * ADR-060 — worker가 공표한 커버리지(`realtime_price_coverage`)와 이 api가 받는 실시세 틱(ADR-038: 모든 인스턴스가 모든 틱을
 * 받는다)을 합쳐 종목별 실시세 상태를 판정한다.
 *
 * STALE 판정에 장 시간표를 쓰지 않는다(api에는 없다). 대신 **이 종목이 같은 시장의 가장 최근 실시세보다 [STALE_AFTER]
 * 넘게 뒤처졌다**를 끊김으로 본다. 장 마감처럼 시장 전체가 함께 조용해지면 누구도 뒤처지지 않으므로 끊김이 아니다(처음
 * 초안은 "시장은 15분 안에 활발, 이 종목은 2분 넘게 조용"이라 매일 장 마감 직후 모든 종목이 STALE이 됐다 — 브랜치 리뷰).
 * 연결 단위 단절(웹소켓)은 여기서 보지 않는다 — 한 시장이 통째로 조용해져 장 마감과 구별되지 않는다. 그건 worker가 연결된
 * 종목만 공표해 NONE으로 드러난다(RealtimeCoveragePublisher).
 * 기동 직후에는 관측이 없으므로 판정하지 않는다(모르는 것을 문제로 단정하지 않는다 — 공표 만료는 NONE이 잡는다).
 */
@Component
class PriceFeedMonitor(
    private val jdbc: JdbcTemplate,
    private val clientRegistry: BrokerageClientRegistry,
    meterRegistry: MeterRegistry,
) {
    private val lastRealTick = ConcurrentHashMap<Long, Instant>()
    private val startedAt = Instant.now()
    private val withoutFeed = mapOf(PriceFeed.NONE to AtomicLong(0), PriceFeed.STALE to AtomicLong(0))

    init {
        withoutFeed.forEach { (reason, count) ->
            Gauge.builder("conditional_order_active_without_feed", count) { it.get().toDouble() }
                .tag("reason", reason.name.lowercase())
                .description("실시세가 없어 발동할 수 없는 실계좌 ACTIVE 조건부 주문 수 (ADR-060)")
                .register(meterRegistry)
        }
    }

    // 컨슈머 스레드에서 동기로 돈다 — 맵 기록 하나라 가볍다. 합성 틱은 조건에서 걸러진다. 다른 리스너(조건부 주문 평가기의
    // @Async 큐 거부 등)가 예외를 던져도 관측이 빠지지 않게 가장 먼저 돈다.
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(condition = "#event.provenance.source.real")
    fun onRealTick(event: MarketTickReceivedEvent) = recordRealTick(event.tick.stockId, Instant.now())

    /** 실시세 관측을 기록한다. 늦게 도착한 과거 시각이 최신 값을 되돌리지 않는다. */
    fun recordRealTick(stockId: Long, at: Instant) {
        lastRealTick.merge(stockId, at) { old, new -> if (new.isAfter(old)) new else old }
    }

    /** 공표가 신선한 커버리지에 있는가. 조건부 주문 생성의 관문. */
    fun isCovered(stockId: Long): Boolean =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM realtime_price_coverage WHERE stock_id = ? AND published_at > now() - make_interval(secs => ?)",
            Long::class.java, stockId, PUBLICATION_TTL.seconds.toDouble(),
        )!! > 0

    fun feedStatus(stockIds: Collection<Long>, now: Instant = Instant.now()): Map<Long, PriceFeed> {
        if (stockIds.isEmpty()) return emptyMap()
        val coveredMarket = coverage()
        val marketLatest = coveredMarket.entries
            .mapNotNull { (id, market) -> lastRealTick[id]?.let { market to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, times) -> times.max() }
        val observing = Duration.between(startedAt, now) >= STALE_AFTER
        return stockIds.associateWith { id ->
            val market = coveredMarket[id] ?: return@associateWith PriceFeed.NONE
            val latestInMarket = marketLatest[market] ?: return@associateWith PriceFeed.LIVE   // 시장 관측 없음 — 판정 안 함
            val last = lastRealTick[id]
            val stale = observing && (last == null || Duration.between(last, latestInMarket) > STALE_AFTER)
            if (stale) PriceFeed.STALE else PriceFeed.LIVE
        }
    }

    /** 실계좌 ACTIVE 조건부 주문 중 실시세가 없는 것을 센다 → `ConditionalOrdersWithoutPriceFeed` Ticket. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun refreshGauge() {
        val active = jdbc.query(
            """
            SELECT co.stock_id, ba.provider FROM conditional_orders co
            JOIN brokerage_accounts ba ON ba.id = co.account_id
            WHERE co.status = 'ACTIVE'
            """.trimIndent(),
        ) { rs, _ -> rs.getLong("stock_id") to BrokerageProvider.valueOf(rs.getString("provider")) }
            .filter { (_, provider) -> clientRegistry.movesRealMoney(provider) }
            .map { it.first }
        val status = feedStatus(active.toSet())
        withoutFeed.forEach { (reason, count) -> count.set(active.count { status[it] == reason }.toLong()) }
    }

    private fun coverage(): Map<Long, String> =
        jdbc.query(
            """
            SELECT c.stock_id, s.market FROM realtime_price_coverage c JOIN stocks s ON s.id = c.stock_id
            WHERE c.published_at > now() - make_interval(secs => ?)
            """.trimIndent(),
            { rs, _ -> rs.getLong("stock_id") to rs.getString("market") },
            PUBLICATION_TTL.seconds.toDouble(),
        ).toMap()

    companion object {
        /** worker는 60초마다 공표한다. 이보다 오래되면 worker가 멈춘 것으로 보고 커버리지를 인정하지 않는다. */
        val PUBLICATION_TTL: Duration = Duration.ofMinutes(5)
        /** 시장의 가장 최근 실시세보다 이만큼 뒤처지면 끊김. 체결 틱이라 거래가 뜸한 종목도 있어 넉넉히 둔다. */
        val STALE_AFTER: Duration = Duration.ofMinutes(5)
    }
}
