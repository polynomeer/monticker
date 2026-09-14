package com.monticker.api.paper.application

import com.monticker.api.matching.events.OrderFilledEvent
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.paper.events.PaperTradeExecutedEvent
import com.monticker.api.paper.infrastructure.PaperTradeRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal

/** ADR-047 — 매칭 체결 하나가 계좌 실행 기록·포지션·정산·원장 이벤트 하나씩을 만든다. */
class PaperExecutionListenerTest {

    private val tradeRepo = mockk<PaperTradeRepository>()
    private val projection = mockk<PortfolioPositionProjection>(relaxed = true)
    private val settlementService = mockk<PaperSettlementService>(relaxed = true)
    private val jdbc = mockk<JdbcTemplate>()
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)
    private val listener = PaperExecutionListener(tradeRepo, projection, settlementService, jdbc, events)

    private fun filled(side: String) = OrderFilledEvent(
        orderId = 10L, userId = 1L, stockId = 5L, fillId = 77L, side = side,
        quantity = 3, fillPrice = BigDecimal("65000"), amount = BigDecimal("195000"),
    )

    @Test
    fun `a BUY fill is mirrored into paper_trades with the fill link and flows to position, settlement and ledger`() {
        every { tradeRepo.findByFillId(77L) } returns null
        val saved = slot<PaperTrade>()
        every { tradeRepo.save(capture(saved)) } answers { PaperTrade(id = 500L, userId = 1L, stockId = 5L, side = "BUY", quantity = 3,
            price = BigDecimal("65000"), amount = BigDecimal("195000"), fillId = 77L) }
        every { jdbc.query(match<String> { it.contains("FROM paper_accounts") }, any<RowMapper<BigDecimal>>(), 1L) } returns listOf(BigDecimal("9805000"))
        val published = slot<Any>()
        every { events.publishEvent(capture(published)) } returns Unit

        listener.onOrderFilled(filled("BUY"))

        assertThat(saved.captured.fillId).isEqualTo(77L)
        assertThat(saved.captured.price).isEqualByComparingTo("65000")
        verify { projection.onBuy(1L, 5L, 3, BigDecimal("195000")) }
        verify { settlementService.createPending(match { it.id == 500L }) }
        val ev = published.captured as PaperTradeExecutedEvent
        assertThat(ev.tradeId).isEqualTo(500L)                        // 원장 paper_trade_id = paper_trades.id
        assertThat(ev.balanceAfter).isEqualByComparingTo("9805000")   // 사가가 갱신한 잔고를 같은 트랜잭션에서 읽는다
    }

    @Test
    fun `a SELL fill reduces the position`() {
        every { tradeRepo.findByFillId(77L) } returns null
        every { tradeRepo.save(any()) } answers { firstArg<PaperTrade>() }
        every { jdbc.query(any<String>(), any<RowMapper<BigDecimal>>(), 1L) } returns listOf(BigDecimal.TEN)

        listener.onOrderFilled(filled("SELL"))

        verify { projection.onSell(1L, 5L, 3) }
        verify(exactly = 0) { projection.onBuy(any(), any(), any(), any()) }
    }

    // Outbox 재전송·중복 발행에도 계좌 기록은 한 번만
    @Test
    fun `a fill already mirrored is ignored`() {
        every { tradeRepo.findByFillId(77L) } returns PaperTrade(id = 1L, userId = 1L, stockId = 5L, side = "BUY", quantity = 3,
            price = BigDecimal.ONE, amount = BigDecimal.ONE, fillId = 77L)

        listener.onOrderFilled(filled("BUY"))

        verify(exactly = 0) { tradeRepo.save(any()) }
        verify(exactly = 0) { events.publishEvent(any()) }
    }
}
