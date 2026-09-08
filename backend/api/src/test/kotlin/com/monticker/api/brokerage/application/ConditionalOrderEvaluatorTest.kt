package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import com.monticker.api.marketdata.domain.PriceTick
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class ConditionalOrderEvaluatorTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val brokerageService = mockk<BrokerageService>()
    private val evaluator = ConditionalOrderEvaluator(jdbc, brokerageService)

    private fun stubActiveRow(
        id: Long = 1L, userId: Long = 1L, symbol: String = "005930", side: String = "SELL",
        triggerType: String = "STOP_LOSS", triggerPrice: BigDecimal = BigDecimal("70000"),
        orderType: String = "MARKET", limitPrice: BigDecimal? = null, quantity: Int = 10,
        ocoGroupId: String? = null,
    ) {
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            @Suppress("UNCHECKED_CAST")
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet> {
                every { getLong("id") } returns id
                every { getLong("user_id") } returns userId
                every { getString("symbol") } returns symbol
                every { getString("side") } returns side
                every { getString("trigger_type") } returns triggerType
                every { getBigDecimal("trigger_price") } returns triggerPrice
                every { getString("order_type") } returns orderType
                every { getBigDecimal("limit_price") } returns limitPrice
                every { getInt("quantity") } returns quantity
                every { getString("oco_group_id") } returns ocoGroupId
            }
            listOf(mapper.mapRow(rs, 0))
        }
    }

    private fun tick(stockId: Long, price: String) =
        MarketTickReceivedEvent(PriceTick(stockId, "005930", BigDecimal(price), 10L, Instant.now()))

    private fun makeOrder(id: Long, status: BrokerageOrderStatus, rejectReason: String? = null): BrokerageOrder =
        BrokerageOrder(
            id = id, userId = 1L, accountId = 1L, symbol = "005930",
            side = OrderSide.SELL, orderType = OrderType.MARKET, quantity = 10,
            status = status, rejectReason = rejectReason,
        )

    @Test
    fun `트리거 조건을 만족하면 원자적으로 발동시켜 실제 주문을 제출한다`() {
        stubActiveRow()
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { jdbc.update(match<String> { it.contains("SET status = 'EXECUTED'") }, *anyVararg()) } returns 1
        val orderSlot = slot<BrokerageOrderRequest>()
        every { brokerageService.submitOrder(1L, capture(orderSlot)) } returns makeOrder(100L, BrokerageOrderStatus.FILLED)

        // 70000 이하 -> STOP_LOSS 발동
        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        assertThat(orderSlot.captured.symbol).isEqualTo("005930")
        assertThat(orderSlot.captured.side).isEqualTo("SELL")
        assertThat(orderSlot.captured.orderType).isEqualTo("MARKET")
        assertThat(orderSlot.captured.quantity).isEqualTo(10)
        verify { jdbc.update(match<String> { it.contains("SET status = 'EXECUTED'") }, 100L, any(), 1L) }
    }

    @Test
    fun `트리거 조건을 만족하지 않으면 아무것도 하지 않는다`() {
        stubActiveRow(triggerPrice = BigDecimal("70000"))

        evaluator.onTick(tick(stockId = 1L, price = "71000"))  // 70000 이하가 아님

        verify(exactly = 0) { brokerageService.submitOrder(any(), any()) }
    }

    @Test
    fun `이미 다른 스레드가 가져간 조건은 중복 발동하지 않는다`() {
        stubActiveRow()
        // 영향받은 행 0 -> 다른 스레드/이전 이벤트가 이미 TRIGGERED로 바꿔둔 상태
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 0

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify(exactly = 0) { brokerageService.submitOrder(any(), any()) }
    }

    @Test
    fun `증권사가 거부하면 FAILED로 기록하고 OCO 형제를 취소한다`() {
        val groupId = UUID.randomUUID()
        stubActiveRow(ocoGroupId = groupId.toString())
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { jdbc.update(match<String> { it.contains("SET status = 'FAILED'") }, *anyVararg()) } returns 1
        every { jdbc.update(match<String> { it.contains("oco_group_id = ?") }, *anyVararg()) } returns 1
        every { brokerageService.submitOrder(1L, any()) } returns makeOrder(100L, BrokerageOrderStatus.REJECTED, "리스크 한도 초과")

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify { jdbc.update(match<String> { it.contains("SET status = 'FAILED'") }, "리스크 한도 초과", 100L, any(), 1L) }
        verify { jdbc.update(match<String> { it.contains("oco_group_id = ?") }, any(), groupId, 1L) }
    }

    @Test
    fun `브로커 호출 자체가 예외를 던지면 FAILED로 기록하고 executed_order_id는 null이다`() {
        stubActiveRow()
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { jdbc.update(match<String> { it.contains("SET status = 'FAILED'") }, *anyVararg()) } returns 1
        every { brokerageService.submitOrder(1L, any()) } throws IllegalStateException("리스크 게이트 거부")

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify { jdbc.update(match<String> { it.contains("SET status = 'FAILED'") }, "리스크 게이트 거부", null, any(), 1L) }
    }
}
