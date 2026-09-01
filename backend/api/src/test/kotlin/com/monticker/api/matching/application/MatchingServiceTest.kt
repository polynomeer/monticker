package com.monticker.api.matching.application

import com.monticker.api.common.domain.Price
import com.monticker.api.matching.domain.Order
import com.monticker.api.matching.domain.OrderSide
import com.monticker.api.matching.domain.OrderStatus
import com.monticker.api.matching.domain.OrderType
import com.monticker.api.matching.infrastructure.FillRepository
import com.monticker.api.matching.infrastructure.OrderRepository
import com.monticker.api.matching.saga.OrderSagaOrchestrator
import com.monticker.api.matching.statemachine.OrderStateMachineService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Optional

class MatchingServiceTest {

    private val orderRepo = mockk<OrderRepository>()
    private val fillRepo = mockk<FillRepository>()
    private val fillQueryService = mockk<FillQueryService>(relaxed = true)
    private val orderBookService = mockk<MatchingOrderBookService>(relaxed = true)
    private val riskChecker = mockk<RiskCheckerService>()
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val stateMachineService = mockk<OrderStateMachineService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val sagaOrchestrator = mockk<OrderSagaOrchestrator>(relaxed = true)

    private val service = MatchingService(orderRepo, fillRepo, fillQueryService, orderBookService, riskChecker, jdbc, stateMachineService, eventPublisher, sagaOrchestrator)

    private val userId = 1L
    private val stockId = 100L

    // REJECTED 케이스는 RiskCheckedAspect 에서 처리 — RiskCheckedAspectTest 참고
    // 체결/현금예약/오더북 제출 등 실제 주문 처리 로직은 OrderSagaOrchestrator로 이동했다 (ADR-011).
    // submitOrder는 이제 순수 위임이라 여기서는 위임 자체만 검증하고, 로직 테스트는
    // OrderSagaOrchestratorTest에서 다룬다.

    @Test
    fun `submitOrder delegates to the saga orchestrator and returns its response unchanged`() {
        val req = SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10)
        val expected = mockk<com.monticker.api.matching.application.SubmitOrderResponse>()
        every { sagaOrchestrator.execute(userId, req) } returns expected

        val response = service.submitOrder(userId, req)

        assertThat(response).isSameAs(expected)
        verify { sagaOrchestrator.execute(userId, req) }
    }

    @Test
    fun `cancelOrder succeeds for a user's own pending order`() {
        val order = Order(
            id = 1L, userId = userId, stockId = stockId,
            side = OrderSide.BUY, orderType = OrderType.LIMIT,
            quantity = 10, limitPrice = Price.of("900"),
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
            quantity = 10, limitPrice = Price.of("900"),
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
            quantity = 10, limitPrice = Price.of("900"),
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
