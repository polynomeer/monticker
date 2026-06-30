package com.monticker.api.matching.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.matching.domain.Order
import com.monticker.api.matching.domain.OrderSide
import com.monticker.api.matching.domain.OrderStatus
import com.monticker.api.matching.domain.OrderType
import com.monticker.api.matching.infrastructure.FillRepository
import com.monticker.api.matching.infrastructure.OrderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.Optional

class MatchingServiceTest {

    private val orderRepo = mockk<OrderRepository>()
    private val fillRepo = mockk<FillRepository>()
    private val orderBookService = mockk<MatchingOrderBookService>(relaxed = true)
    private val riskChecker = mockk<RiskCheckerService>()
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private val service = MatchingService(orderRepo, fillRepo, orderBookService, riskChecker, jdbc, objectMapper)

    private val userId = 1L
    private val stockId = 100L
    private val currentPrice = BigDecimal("1000")

    private fun stubStockExistsAndPrice() {
        every { jdbc.queryForObject("SELECT COUNT(*) FROM stocks WHERE id = ?", Long::class.java, stockId) } returns 1L
        every {
            jdbc.queryForObject(
                "SELECT close FROM candles_1m WHERE stock_id = ? ORDER BY candle_time DESC LIMIT 1",
                BigDecimal::class.java, stockId
            )
        } returns currentPrice
    }

    private fun stubAccountCash(cash: BigDecimal = BigDecimal("10000000")) {
        every {
            jdbc.queryForObject("SELECT cash FROM paper_accounts WHERE user_id = ?", BigDecimal::class.java, userId)
        } returns cash
    }

    private fun approvedRiskResult() = RiskCheckResult(approved = true, blockedBy = null, severity = "APPROVED", checks = emptyList())
    private fun blockedRiskResult(reason: String = "DailyLossRule") =
        RiskCheckResult(approved = false, blockedBy = reason, severity = "BLOCKED", checks = emptyList())

    private fun savedOrderSlot(): io.mockk.MockKMatcherScope.() -> Order = { any() }

    @Test
    fun `submitOrder saves order as REJECTED when risk check is blocked and does not submit to order book`() {
        stubStockExistsAndPrice()
        every { riskChecker.check(userId, stockId, "BUY", 10, currentPrice) } returns blockedRiskResult("DailyLossRule")

        val savedSlot = slot<Order>()
        every { orderRepo.save(capture(savedSlot)) } answers { savedSlot.captured }

        val response = service.submitOrder(
            userId,
            SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10)
        )

        assertThat(response.order.status).isEqualTo("REJECTED")
        assertThat(response.order.rejectReason).contains("DailyLossRule")
        assertThat(response.fills).isEmpty()
        verify(exactly = 0) { orderBookService.submit(any()) }
        verify(exactly = 1) { orderRepo.save(any()) }
    }

    @Test
    fun `submitOrder fully fills a MARKET order when risk check passes`() {
        stubStockExistsAndPrice()
        stubAccountCash()
        every { riskChecker.check(userId, stockId, "BUY", 10, currentPrice) } returns approvedRiskResult()

        val savedOrders = mutableListOf<Order>()
        every { orderRepo.save(capture(savedOrders)) } answers { savedOrders.last() }

        val fillSlot = slot<com.monticker.api.matching.domain.Fill>()
        every { fillRepo.save(capture(fillSlot)) } answers {
            // simulate generated id
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

        val response = service.submitOrder(
            userId,
            SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10)
        )

        assertThat(response.order.status).isEqualTo("FILLED")
        assertThat(response.fills).hasSize(1)
        assertThat(response.fills[0].fillPrice).isEqualByComparingTo(currentPrice)
        verify { fillRepo.save(any()) }
        verify(atLeast = 1) { jdbc.update(match<String> { it.contains("ledger_events") }, *anyVararg()) }
    }

    @Test
    fun `submitOrder leaves LIMIT order unfilled and submits it to order book when price does not cross`() {
        stubStockExistsAndPrice()
        stubAccountCash()
        val limitPrice = BigDecimal("900")
        every { riskChecker.check(userId, stockId, "BUY", 10, limitPrice) } returns approvedRiskResult()

        val savedOrders = mutableListOf<Order>()
        every { orderRepo.save(capture(savedOrders)) } answers { savedOrders.last() }

        val response = service.submitOrder(
            userId,
            SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "LIMIT", quantity = 10, limitPrice = limitPrice)
        )

        assertThat(response.order.status).isEqualTo("PENDING")
        assertThat(response.fills).isEmpty()
        verify { orderBookService.submit(any()) }
    }

    @Test
    fun `submitOrder for MARKET order documents actual no-liquidity behavior — fills at current price regardless of book`() {
        // NOTE: MatchingService.submitOrder for MARKET orders does NOT consult the
        // order book at all for fill determination — it always "fills" a MARKET order
        // at currentPrice directly (see fillPrice `when` block: `req.orderType == "MARKET" -> currentPrice`).
        // The order book (`orderBookService.submit`) is only invoked for the LIMIT-no-fill path.
        // So there is no "zero liquidity -> cancelled/rejected" branch for MARKET orders in this service;
        // it always treats MARKET orders as fillable at currentPrice without checking real liquidity.
        stubStockExistsAndPrice()
        stubAccountCash()
        every { riskChecker.check(userId, stockId, "BUY", 10, currentPrice) } returns approvedRiskResult()

        val savedOrders = mutableListOf<Order>()
        every { orderRepo.save(capture(savedOrders)) } answers { savedOrders.last() }
        every { fillRepo.save(any()) } answers {
            val f = firstArg<com.monticker.api.matching.domain.Fill>()
            com.monticker.api.matching.domain.Fill(
                id = 1L, orderId = f.orderId, userId = f.userId, stockId = f.stockId,
                side = f.side, quantity = f.quantity, fillPrice = f.fillPrice, amount = f.amount, fee = f.fee,
            )
        }

        val response = service.submitOrder(
            userId,
            SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10)
        )

        assertThat(response.order.status).isEqualTo("FILLED")
        verify(exactly = 0) { orderBookService.submit(any()) }
    }

    @Test
    fun `cancelOrder succeeds for a user's own pending order`() {
        val order = Order(
            id = 1L, userId = userId, stockId = stockId,
            side = OrderSide.BUY, orderType = OrderType.LIMIT,
            quantity = 10, limitPrice = BigDecimal("900"),
            status = OrderStatus.PENDING,
        )
        every { orderRepo.findById(1L) } returns Optional.of(order)
        every { orderBookService.cancel(stockId, 1L, OrderSide.BUY) } returns true
        every { orderRepo.save(any()) } answers { firstArg() }

        val result = service.cancelOrder(userId, 1L)

        assertThat(result.status).isEqualTo("CANCELLED")
        verify { orderBookService.cancel(stockId, 1L, OrderSide.BUY) }
    }

    @Test
    fun `cancelOrder throws for an order belonging to a different user`() {
        val order = Order(
            id = 1L, userId = 999L, stockId = stockId,
            side = OrderSide.BUY, orderType = OrderType.LIMIT,
            quantity = 10, limitPrice = BigDecimal("900"),
            status = OrderStatus.PENDING,
        )
        every { orderRepo.findById(1L) } returns Optional.of(order)

        assertThatThrownBy { service.cancelOrder(userId, 1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `cancelOrder throws for an order that is already FILLED`() {
        val order = Order(
            id = 1L, userId = userId, stockId = stockId,
            side = OrderSide.BUY, orderType = OrderType.LIMIT,
            quantity = 10, limitPrice = BigDecimal("900"),
            status = OrderStatus.FILLED,
        )
        every { orderRepo.findById(1L) } returns Optional.of(order)

        assertThatThrownBy { service.cancelOrder(userId, 1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `cancelOrder throws when order does not exist`() {
        every { orderRepo.findById(404L) } returns Optional.empty()

        assertThatThrownBy { service.cancelOrder(userId, 404L) }
            .isInstanceOf(NoSuchElementException::class.java)
    }
}
