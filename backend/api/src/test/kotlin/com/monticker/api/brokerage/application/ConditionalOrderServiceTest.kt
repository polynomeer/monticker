package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageAccount
import com.monticker.api.brokerage.domain.BrokerageProvider
import com.monticker.api.brokerage.domain.ConditionalOrder
import com.monticker.api.brokerage.domain.ConditionalOrderStatus
import com.monticker.api.brokerage.domain.ConditionalTriggerType
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.brokerage.infrastructure.BrokerageAccountRepository
import com.monticker.api.brokerage.infrastructure.ConditionalOrderRepository
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.Optional

class ConditionalOrderServiceTest {

    private val accountRepo = mockk<BrokerageAccountRepository>()
    private val conditionalOrderRepo = mockk<ConditionalOrderRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = ConditionalOrderService(accountRepo, conditionalOrderRepo, jdbc)

    private fun makeAccount() = BrokerageAccount(id = 1L, userId = 1L, provider = BrokerageProvider.MOCK, accountNumber = "12345678")

    private fun stubAccountAndStock() {
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(makeAccount())
        every { jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, "005930") } returns 1L
    }

    @Test
    fun `연동된 계좌가 없으면 등록할 수 없다`() {
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.empty()

        assertThatThrownBy {
            service.create(1L, "005930", OrderSide.SELL, 10, ConditionalOrderLeg(ConditionalTriggerType.STOP_LOSS, BigDecimal("70000"), OrderType.MARKET))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `존재하지 않는 종목이면 등록할 수 없다`() {
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(makeAccount())
        every { jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, "999999") } throws IllegalStateException("not found")

        assertThatThrownBy {
            service.create(1L, "999999", OrderSide.SELL, 10, ConditionalOrderLeg(ConditionalTriggerType.STOP_LOSS, BigDecimal("70000"), OrderType.MARKET))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `지정가인데 가격이 없으면 등록할 수 없다`() {
        stubAccountAndStock()

        assertThatThrownBy {
            service.create(1L, "005930", OrderSide.SELL, 10, ConditionalOrderLeg(ConditionalTriggerType.STOP_LOSS, BigDecimal("70000"), OrderType.LIMIT, limitPrice = null))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `단일 트리거 조건부 주문을 정상 등록한다`() {
        stubAccountAndStock()
        val savedSlot = slot<ConditionalOrder>()
        every { conditionalOrderRepo.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.create(1L, "005930", OrderSide.SELL, 10, ConditionalOrderLeg(ConditionalTriggerType.STOP_LOSS, BigDecimal("70000"), OrderType.MARKET))

        assertThat(result.status).isEqualTo(ConditionalOrderStatus.ACTIVE)
        assertThat(result.ocoGroupId).isNull()
        assertThat(result.accountId).isEqualTo(1L)
        assertThat(result.stockId).isEqualTo(1L)
    }

    @Test
    fun `OCO는 정확히 2개의 조건이 필요하다`() {
        stubAccountAndStock()

        assertThatThrownBy {
            service.createOco(1L, "005930", OrderSide.SELL, 10, listOf(ConditionalOrderLeg(ConditionalTriggerType.STOP_LOSS, BigDecimal("70000"), OrderType.MARKET)))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `OCO 등록 시 두 조건이 같은 그룹 ID를 공유한다`() {
        stubAccountAndStock()
        every { conditionalOrderRepo.saveAll(any<List<ConditionalOrder>>()) } answers { firstArg() }

        val result = service.createOco(
            1L, "005930", OrderSide.SELL, 10,
            listOf(
                ConditionalOrderLeg(ConditionalTriggerType.STOP_LOSS, BigDecimal("65000"), OrderType.MARKET),
                ConditionalOrderLeg(ConditionalTriggerType.TAKE_PROFIT, BigDecimal("80000"), OrderType.MARKET),
            ),
        )

        assertThat(result).hasSize(2)
        assertThat(result[0].ocoGroupId).isNotNull().isEqualTo(result[1].ocoGroupId)
    }

    @Test
    fun `ACTIVE 상태가 아니면 취소할 수 없다`() {
        val order = ConditionalOrder(
            id = 1L, userId = 1L, accountId = 1L, stockId = 1L, symbol = "005930",
            side = OrderSide.SELL, triggerType = ConditionalTriggerType.STOP_LOSS, triggerPrice = BigDecimal("70000"),
            orderType = OrderType.MARKET, quantity = 10, status = ConditionalOrderStatus.EXECUTED,
        )
        every { conditionalOrderRepo.findById(1L) } returns Optional.of(order)

        assertThatThrownBy { service.cancel(1L, 1L) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
