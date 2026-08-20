package com.monticker.api.matching.application

import com.monticker.api.matching.domain.Order
import com.monticker.api.matching.domain.OrderSide
import com.monticker.api.matching.domain.OrderStatus
import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import com.monticker.api.matching.infrastructure.FillRepository
import com.monticker.api.matching.infrastructure.OrderRepository
import com.monticker.api.matching.statemachine.OrderStateMachineService
import com.monticker.api.matching.statemachine.OrderStates
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.Optional

/**
 * MatchingService: 주문 제출/취소의 핵심 자금/체결 로직 검증.
 *
 * jdbc(JdbcTemplate), orderRepo/fillRepo(JPA 리포지토리), stateMachineService(Spring
 * StateMachine 파사드), eventPublisher(ApplicationEventPublisher)는 모두 이 서비스가
 * 소유하지 않는 시스템 경계(DB, 상태 기계, 이벤트 버스)이므로 모킹한다.
 * MatchingOrderBookService는 순수 인메모리 컬렉션이라 실제 인스턴스를 사용한다.
 */
class MatchingServiceTest {

    private lateinit var orderRepo: OrderRepository
    private lateinit var fillRepo: FillRepository
    private lateinit var fillQueryService: FillQueryService
    private lateinit var orderBookService: MatchingOrderBookService
    private lateinit var riskChecker: RiskCheckerService
    private lateinit var jdbc: JdbcTemplate
    private lateinit var stateMachineService: OrderStateMachineService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: MatchingService

    private val userId = 42L
    private val stockId = 100L

    @BeforeEach
    fun setUp() {
        orderRepo = mockk()
        fillRepo = mockk()
        fillQueryService = mockk()
        orderBookService = MatchingOrderBookService()
        riskChecker = mockk()
        jdbc = mockk()
        stateMachineService = mockk()
        eventPublisher = mockk(relaxed = true)

        service = MatchingService(
            orderRepo = orderRepo,
            fillRepo = fillRepo,
            fillQueryService = fillQueryService,
            orderBookService = orderBookService,
            riskChecker = riskChecker,
            jdbc = jdbc,
            stateMachineService = stateMachineService,
            eventPublisher = eventPublisher,
        )

        every { orderRepo.save(any()) } answers { firstArg() }
        every { fillRepo.save(any()) } answers { firstArg() }
        every { stateMachineService.transition(any(), any(), any()) } returns OrderStates.FILLED
    }

    private fun stubCurrentPrice(price: BigDecimal) {
        every {
            jdbc.queryForObject(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, any())
        } returns price
    }

    private fun stubCash(cash: BigDecimal) {
        every {
            jdbc.queryForObject(
                match<String> { it.contains("paper_accounts") && it.contains("cash") },
                BigDecimal::class.java,
                any(),
            )
        } returns cash
    }

    private fun stubStockExists(exists: Boolean = true) {
        every {
            jdbc.queryForObject(match<String> { it.contains("COUNT(*)") && it.contains("stocks") }, Long::class.java, any())
        } returns if (exists) 1L else 0L
    }

    private fun stubAdjustCash(deltaSlot: io.mockk.CapturingSlot<BigDecimal> = slot()) {
        every {
            jdbc.update(match<String> { it.contains("cash = cash + ?") }, capture(deltaSlot), any())
        } returns 1
    }

    // ─── submitOrder: 정상 체결 ─────────────────────────────────────────────

    @Test
    fun `LIMIT BUY 주문이 지정가보다 낮은 현재가에 체결되면 예약금과 체결금액의 차액을 환불한다`() {
        stubCurrentPrice(BigDecimal("50000"))
        stubCash(BigDecimal("10000000"))
        stubStockExists()
        val deltas = mutableListOf<BigDecimal>()
        // capture every delta passed to adjustCash, in call order
        val slotCapture = slot<BigDecimal>()
        every {
            jdbc.update(match<String> { it.contains("cash = cash + ?") }, capture(slotCapture), any())
        } answers {
            deltas.add(slotCapture.captured)
            1
        }

        val req = SubmitOrderRequest(
            stockId = stockId, side = "BUY", orderType = "LIMIT",
            quantity = 10, limitPrice = BigDecimal("52000"),
        )
        val response = service.submitOrder(userId, req)

        assertEquals("FILLED", response.order.status)
        assertEquals(1, response.fills.size)
        assertEquals(0, response.fills[0].amount.compareTo(BigDecimal("500000")))
        assertEquals("주문 체결 완료", response.message)

        // 예약(reserve) -520000 후 환불(refund) +20000 순서로 두 번 호출된다
        assertEquals(2, deltas.size)
        assertEquals(0, deltas[0].compareTo(BigDecimal("-520000")))
        assertEquals(0, deltas[1].compareTo(BigDecimal("20000")))

        val eventSlot = slot<OrderFilledEvent>()
        verify { eventPublisher.publishEvent(capture(eventSlot)) }
        assertEquals(userId, eventSlot.captured.userId)
        assertEquals(0, eventSlot.captured.fillPrice.compareTo(BigDecimal("50000")))
        assertEquals(0, eventSlot.captured.amount.compareTo(BigDecimal("500000")))
    }

    @Test
    fun `MARKET SELL 주문은 즉시 체결되고 체결금액만큼 잔고가 증가한다`() {
        stubCurrentPrice(BigDecimal("50000"))
        stubStockExists()
        val deltaSlot = slot<BigDecimal>()
        every {
            jdbc.update(match<String> { it.contains("cash = cash + ?") }, capture(deltaSlot), any())
        } returns 1

        val req = SubmitOrderRequest(stockId = stockId, side = "SELL", orderType = "MARKET", quantity = 5)
        val response = service.submitOrder(userId, req)

        assertEquals("FILLED", response.order.status)
        assertEquals(1, response.fills.size)
        assertEquals(0, deltaSlot.captured.compareTo(BigDecimal("250000")))

        val eventSlot = slot<OrderFilledEvent>()
        verify { eventPublisher.publishEvent(capture(eventSlot)) }
        assertEquals("SELL", eventSlot.captured.side)
    }

    @Test
    fun `LIMIT BUY 주문이 즉시 체결 조건을 만족하지 않으면 주문북에 등록되고 미체결로 남는다`() {
        stubCurrentPrice(BigDecimal("50000"))
        stubCash(BigDecimal("10000000"))
        stubStockExists()
        stubAdjustCash()

        val req = SubmitOrderRequest(
            stockId = stockId, side = "BUY", orderType = "LIMIT",
            quantity = 10, limitPrice = BigDecimal("48000"),
        )
        val response = service.submitOrder(userId, req)

        assertEquals("PENDING", response.order.status)
        assertTrue(response.fills.isEmpty())
        assertEquals("주문 접수 완료 (미체결)", response.message)
        verify(exactly = 0) { eventPublisher.publishEvent(any<OrderFilledEvent>()) }
    }

    // ─── submitOrder: 검증/에러 ─────────────────────────────────────────────

    @Test
    fun `잔고가 부족하면 BUY 주문이 거부되고 주문이 저장되지 않는다`() {
        stubCurrentPrice(BigDecimal("50000"))
        stubCash(BigDecimal("1000"))
        stubStockExists()

        val req = SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.submitOrder(userId, req) }
        assertTrue(ex.message!!.contains("잔고 부족"))
        verify(exactly = 0) { orderRepo.save(any()) }
    }

    @Test
    fun `존재하지 않는 종목에 주문하면 예외가 발생한다`() {
        stubStockExists(exists = false)

        val req = SubmitOrderRequest(stockId = 999L, side = "BUY", orderType = "MARKET", quantity = 1)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.submitOrder(userId, req) }
        assertTrue(ex.message!!.contains("존재하지 않는 종목"))
    }

    @Test
    fun `수량이 0 이하이면 예외가 발생한다`() {
        val req = SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 0)
        assertThrows(IllegalArgumentException::class.java) { service.submitOrder(userId, req) }
    }

    @Test
    fun `잘못된 side 값이면 예외가 발생한다`() {
        val req = SubmitOrderRequest(stockId = stockId, side = "HOLD", orderType = "MARKET", quantity = 1)
        assertThrows(IllegalArgumentException::class.java) { service.submitOrder(userId, req) }
    }

    @Test
    fun `LIMIT 주문에 limitPrice가 없으면 예외가 발생한다`() {
        val req = SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "LIMIT", quantity = 1, limitPrice = null)
        val ex = assertThrows(IllegalArgumentException::class.java) { service.submitOrder(userId, req) }
        assertTrue(ex.message!!.contains("limit_price"))
    }

    // ─── cancelOrder ────────────────────────────────────────────────────────

    @Test
    fun `BUY 주문 취소 시 예약된 금액을 환불하고 취소 이벤트를 발행한다`() {
        val order = Order(
            userId = userId, stockId = stockId, side = OrderSide.BUY,
            orderType = com.monticker.api.matching.domain.OrderType.LIMIT,
            quantity = 10, limitPrice = com.monticker.api.common.domain.Price.of(BigDecimal("50000")),
            status = OrderStatus.PENDING,
        )
        every { orderRepo.findById(1L) } returns Optional.of(order)
        val deltaSlot = slot<BigDecimal>()
        every {
            jdbc.update(match<String> { it.contains("cash = cash + ?") }, capture(deltaSlot), any())
        } returns 1

        val dto = service.cancelOrder(userId, 1L)

        assertEquals("CANCELLED", dto.status)
        assertEquals(0, deltaSlot.captured.compareTo(BigDecimal("500000")))

        val eventSlot = slot<OrderCancelledEvent>()
        verify { eventPublisher.publishEvent(capture(eventSlot)) }
        assertEquals(0, eventSlot.captured.refundAmount.compareTo(BigDecimal("500000")))
    }

    @Test
    fun `본인의 주문이 아니면 취소할 수 없다`() {
        val order = Order(
            userId = 999L, stockId = stockId, side = OrderSide.BUY,
            orderType = com.monticker.api.matching.domain.OrderType.MARKET,
            quantity = 10, status = OrderStatus.PENDING,
        )
        every { orderRepo.findById(1L) } returns Optional.of(order)

        val ex = assertThrows(IllegalArgumentException::class.java) { service.cancelOrder(userId, 1L) }
        assertTrue(ex.message!!.contains("본인의 주문"))
    }

    @Test
    fun `존재하지 않는 주문을 취소하면 예외가 발생한다`() {
        every { orderRepo.findById(1L) } returns Optional.empty()
        assertThrows(NoSuchElementException::class.java) { service.cancelOrder(userId, 1L) }
    }
}
