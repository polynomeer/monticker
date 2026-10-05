package com.monticker.api.brokerage

import com.monticker.api.brokerage.application.PriceFeed
import com.monticker.api.brokerage.application.PriceFeedMonitor
import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.brokerage.infrastructure.BrokerageClient
import com.monticker.api.brokerage.infrastructure.BrokerageClientRegistry
import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import com.monticker.api.marketdata.domain.PriceSource
import com.monticker.api.marketdata.domain.PriceTick
import com.monticker.api.marketdata.domain.TickProvenance
import com.monticker.api.support.PostgresIntegrationTest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** ADR-060 — 공표된 커버리지 + 관측한 실시세로 종목별 실시세 상태를 판정한다. 실제 스키마(V53)에서. */
class PriceFeedMonitorIntegrationTest : PostgresIntegrationTest() {

    private val registry = BrokerageClientRegistry(BrokerageProvider.entries.associateWith {
        mockk<BrokerageClient> { every { movesRealMoney } returns true }
    })
    private val monitor by lazy { PriceFeedMonitor(jdbcTemplate, registry, SimpleMeterRegistry()) }

    @AfterEach fun clear() { jdbcTemplate.update("DELETE FROM realtime_price_coverage") }

    private fun stock(market: String = "KOSPI"): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, 'f', ?, 'KRX') RETURNING id",
        Long::class.java, "F${System.nanoTime() % 100000000}", market,
    )!!

    private fun publish(stockId: Long, agoSeconds: Long = 0) = jdbcTemplate.update(
        "INSERT INTO realtime_price_coverage (stock_id, source, published_at) VALUES (?, 'KIS', now() - make_interval(secs => ?))",
        stockId, agoSeconds.toDouble(),
    )

    private fun realTick(stockId: Long) = monitor.onRealTick(MarketTickReceivedEvent(
        PriceTick(stockId, "X", BigDecimal.ONE, 1, Instant.now()), TickProvenance(PriceSource.KIS, "OPEN", Instant.now()),
    ))

    @Test
    fun `공표가 신선하면 커버리지, 5분 넘게 끊기면 모름(커버리지 아님)`() {
        val fresh = stock(); val stale = stock(); val absent = stock()
        publish(fresh); publish(stale, agoSeconds = 400)

        assertThat(monitor.isCovered(fresh)).isTrue()
        assertThat(monitor.isCovered(stale)).isFalse()
        assertThat(monitor.isCovered(absent)).isFalse()
        assertThat(monitor.feedStatus(listOf(fresh, stale, absent))).isEqualTo(
            mapOf(fresh to PriceFeed.LIVE, stale to PriceFeed.NONE, absent to PriceFeed.NONE),
        )
    }

    @Test
    fun `같은 시장의 최근 실시세보다 5분 넘게 뒤처진 종목만 STALE`() {
        val leader = stock(); val laggard = stock(); val never = stock()
        listOf(leader, laggard, never).forEach { publish(it) }
        val t0 = Instant.now().plusSeconds(600)            // 관측 기간(기동 후 5분)이 지난 시점
        monitor.recordRealTick(laggard, t0.minusSeconds(400))
        monitor.recordRealTick(leader, t0)

        val status = monitor.feedStatus(listOf(leader, laggard, never), now = t0.plusSeconds(1))

        assertThat(status[leader]).isEqualTo(PriceFeed.LIVE)
        assertThat(status[laggard]).isEqualTo(PriceFeed.STALE)   // 시장보다 6분 40초 뒤처졌다
        assertThat(status[never]).isEqualTo(PriceFeed.STALE)     // 시장은 흐르는데 이 종목은 한 번도 안 왔다
    }

    @Test
    fun `장 마감 — 시장 전체가 함께 조용해지면 아무도 STALE이 아니다(매일 오경보가 나던 초안)`() {
        val a = stock(); val b = stock()
        publish(a); publish(b)
        val close = Instant.now().plusSeconds(600)
        monitor.recordRealTick(a, close.minusSeconds(20))
        monitor.recordRealTick(b, close)

        // 마감 10분 뒤에도 둘은 서로 20초 차이일 뿐이다
        val status = monitor.feedStatus(listOf(a, b), now = close.plusSeconds(600))
        assertThat(status.values).containsOnly(PriceFeed.LIVE)
    }

    @Test
    fun `실시세를 한 번도 관측하지 못한 시장은 판정하지 않는다`() {
        val us = stock("NASDAQ")
        publish(us)
        assertThat(monitor.feedStatus(listOf(us), now = Instant.now().plusSeconds(600))[us]).isEqualTo(PriceFeed.LIVE)
    }

    @Test
    fun `기동 직후에는 끊김을 단정하지 않는다`() {
        val a = stock(); val b = stock()
        publish(a); publish(b)
        realTick(a)

        assertThat(monitor.feedStatus(listOf(b))[b]).isEqualTo(PriceFeed.LIVE)
    }
}
