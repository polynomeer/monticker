package com.monticker.api.matching.saga

import com.monticker.api.matching.application.FillQueryService
import com.monticker.api.matching.application.MatchingOrderBookService
import com.monticker.api.matching.application.SubmitOrderRequest
import com.monticker.api.matching.statemachine.OrderStateMachineService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

/**
 * MatchingService.submitOrder는 여기(OrderSagaOrchestrator.execute)로 위임만 한다 — ADR-011.
 * 실제 체결/현금 예약/주문북 제출 로직은 이 클래스에 있으므로 관련 단위 테스트도 여기서 다룬다.
 */
class OrderSagaOrchestratorTest {

    private val sagaRepo = mockk<OrderSagaRepository>(relaxed = true) {
        every { save(any()) } answers { firstArg() }
    }
    private val orderRepo = mockk<com.monticker.api.matching.infrastructure.OrderRepository>()
    private val fillRepo = mockk<com.monticker.api.matching.infrastructure.FillRepository>()
    private val fillQueryService = mockk<FillQueryService>(relaxed = true)
    private val orderBookService = mockk<MatchingOrderBookService>(relaxed = true)
    private val stateMachineService = mockk<OrderStateMachineService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)

    private val orchestrator = OrderSagaOrchestrator(
        sagaRepo, orderRepo, fillRepo, fillQueryService, orderBookService, stateMachineService, eventPublisher, jdbc,
    )

    private val userId = 1L
    private val stockId = 100L
    private val currentPrice = BigDecimal("1000")

    private fun stubStockExistsAndPrice() {
        every { jdbc.queryForObject("SELECT COUNT(*) FROM stocks WHERE id = ?", Long::class.java, stockId) } returns 1L
        every {
            jdbc.queryForObject(
                "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
                BigDecimal::class.java, stockId,
            )
        } returns currentPrice
    }

    private fun stubAccountCash(cash: BigDecimal = BigDecimal("10000000")) {
        every {
            jdbc.queryForObject("SELECT cash FROM paper_accounts WHERE user_id = ?", BigDecimal::class.java, userId)
        } returns cash
    }

    private fun stubOrderAndFillSaves() {
        val savedOrders = mutableListOf<com.monticker.api.matching.domain.Order>()
        every { orderRepo.save(capture(savedOrders)) } answers { savedOrders.last() }

        val fillSlot = slot<com.monticker.api.matching.domain.Fill>()
        every { fillRepo.save(capture(fillSlot)) } answers {
            com.monticker.api.matching.domain.Fill(
                id = 1L,
                orderId = fillSlot.captured.orderId,
                userId = fillSlot.captured.userId,
                stockId = fillSlot.captured.stockId,
                side = fillSlot.captured.side,
                quantity = fillSlot.captured.quantity,
                fillPrice = fillSlot.captured.fillPrice,
                amount = fillSlot.captured.amount,
                fee = fillSlot.captured.fee,
            )
        }
    }

    @Test
    fun `execute fully fills a MARKET order`() {
        stubStockExistsAndPrice()
        stubAccountCash()
        stubOrderAndFillSaves()

        val response = orchestrator.execute(
            userId,
            SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10),
        )

        assertThat(response.order.status).isEqualTo("FILLED")
        assertThat(response.fills).hasSize(1)
        assertThat(response.fills[0].fillPrice).isEqualByComparingTo(currentPrice)
        verify { fillRepo.save(any()) }
        verify { eventPublisher.publishEvent(any<com.monticker.api.matching.events.OrderFilledEvent>()) }
    }

    @Test
    fun `execute leaves a LIMIT order unfilled and submits it to the order book when price does not cross`() {
        stubStockExistsAndPrice()
        stubAccountCash()
        stubOrderAndFillSaves()
        val limitPrice = BigDecimal("900")

        val response = orchestrator.execute(
            userId,
            SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "LIMIT", quantity = 10, limitPrice = limitPrice),
        )

        assertThat(response.order.status).isEqualTo("PENDING")
        assertThat(response.fills).isEmpty()
        verify { orderBookService.submit(any()) }
        verify(exactly = 0) { fillRepo.save(any()) }
    }

    @Test
    fun `execute for a MARKET order documents actual no-liquidity behavior — fills at current price regardless of book`() {
        // NOTE: MARKET 주문은 오더북 체결가/유동성을 전혀 조회하지 않는다 — 항상 currentPrice로
        // "체결"된 것으로 간주한다 (fillPrice `when` 블록의 `orderType == "MARKET" -> currentPrice`).
        // 오더북(orderBookService.submit)은 LIMIT 미체결 경로에서만 호출된다.
        stubStockExistsAndPrice()
        stubAccountCash()
        stubOrderAndFillSaves()

        val response = orchestrator.execute(
            userId,
            SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10),
        )

        assertThat(response.order.status).isEqualTo("FILLED")
        verify(exactly = 0) { orderBookService.submit(any()) }
    }

    @Test
    fun `execute rejects a BUY order when cash is insufficient and runs compensation`() {
        stubStockExistsAndPrice()
        stubAccountCash(cash = BigDecimal("100"))

        org.assertj.core.api.Assertions.assertThatThrownBy {
            orchestrator.execute(
                userId,
                SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        // CASH_RESERVED 단계까지 못 갔으므로 (require 실패가 CASH_RESERVED 진입 이전) 주문/체결 저장은 없어야 함
        verify(exactly = 0) { orderRepo.save(any()) }
        verify(exactly = 0) { fillRepo.save(any()) }
    }
}
