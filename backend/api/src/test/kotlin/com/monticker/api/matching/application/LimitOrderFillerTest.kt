package com.monticker.api.matching.application

import com.monticker.api.common.domain.CandleFreshness
import com.monticker.api.common.domain.LatestClose
import com.monticker.api.common.domain.Price
import com.monticker.api.matching.domain.Fill
import com.monticker.api.matching.domain.Order
import com.monticker.api.matching.domain.OrderSide
import com.monticker.api.matching.domain.OrderStatus
import com.monticker.api.matching.domain.OrderType
import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.matching.events.OrderFilledEvent
import com.monticker.api.matching.infrastructure.FillRepository
import com.monticker.api.matching.infrastructure.OrderRepository
import com.monticker.api.matching.statemachine.OrderStateMachineService
import com.monticker.api.risk.application.RiskCheckResult
import com.monticker.api.risk.application.RiskCheckerService
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
import java.util.Optional

/** ADR-074 — 미체결 지정가 한 건의 체결. 행 락(SKIP LOCKED) → 상태 재확인 → 교차 판정 → 체결·정산·이벤트. */
class LimitOrderFillerTest {
    private val orderRepo = mockk<OrderRepository>()
    private val fillRepo = mockk<FillRepository>()
    private val stateMachine = mockk<OrderStateMachineService>(relaxed = true)
    private val events = mockk<ApplicationEventPublisher>(relaxed = true)
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val book = mockk<MatchingOrderBookService>(relaxed = true)
    private val riskChecker = mockk<RiskCheckerService>()
    private val filler = LimitOrderFiller(orderRepo, fillRepo, stateMachine, events, jdbc, book, riskChecker)

    init {
        every { riskChecker.checkPaperFill(any(), any(), any(), any(), any()) } returns
            RiskCheckResult(approved = true, blockedBy = null, severity = "APPROVED", checks = emptyList())
    }

    private fun order(side: OrderSide, limit: String, qty: Int = 10, status: OrderStatus = OrderStatus.PENDING) = Order(
        id = 7L, userId = 1L, stockId = 100L, side = side, orderType = OrderType.LIMIT, quantity = qty,
        limitPrice = Price.of(limit), status = status,
    )

    private fun stubLock(acquired: Boolean) {
        every { jdbc.query(LimitOrderFiller.LOCK_SQL, any<RowMapper<Long>>(), 7L) } returns if (acquired) listOf(7L) else emptyList()
    }

    private fun stubPrice(price: String, at: java.time.Instant = java.time.Instant.now()) {
        every { jdbc.query(LimitOrderFiller.LATEST_PRICE_SQL, any<RowMapper<LatestClose>>(), 100L) } returns listOf(LatestClose(BigDecimal(price), at))
    }

    private fun stubSaves() {
        every { orderRepo.save(any()) } answers { firstArg() }
        val f = slot<Fill>()
        every { fillRepo.save(capture(f)) } answers {
            Fill(id = 55L, orderId = f.captured.orderId, userId = f.captured.userId, stockId = f.captured.stockId,
                side = f.captured.side, quantity = f.captured.quantity, fillPrice = f.captured.fillPrice, amount = f.captured.amount)
        }
    }

    @Test
    fun `fills a crossed BUY at the current price and refunds the reserved difference`() {
        val o = order(OrderSide.BUY, "1000")
        stubLock(true); stubPrice("950"); stubSaves()
        every { orderRepo.findById(7L) } returns Optional.of(o)

        assertThat(filler.fillIfCrossed(7L)).isEqualTo(LimitFillOutcome.FILLED)

        assertThat(o.status).isEqualTo(OrderStatus.FILLED)
        // 예약은 1000×10 = 10,000 — 체결 950×10 = 9,500 → 500 환불
        verify { jdbc.update(match<String> { it.startsWith("UPDATE paper_accounts SET cash = cash +") }, match<BigDecimal> { it.compareTo(BigDecimal("500")) == 0 }, 1L) }
        val ev = slot<OrderFilledEvent>()
        verify { events.publishEvent(capture(ev)) }
        assertThat(ev.captured.fillId).isEqualTo(55L)
        assertThat(ev.captured.fillPrice).isEqualByComparingTo("950")
        assertThat(ev.captured.quantity).isEqualTo(10)
    }

    @Test
    fun `fills a crossed SELL and credits the proceeds`() {
        val o = order(OrderSide.SELL, "1000", qty = 3)
        stubLock(true); stubPrice("1020"); stubSaves()
        every { orderRepo.findById(7L) } returns Optional.of(o)
        every { jdbc.query(match<String> { it.contains("FROM portfolio_positions") }, any<RowMapper<Int>>(), 1L, 100L) } returns listOf(3)

        assertThat(filler.fillIfCrossed(7L)).isEqualTo(LimitFillOutcome.FILLED)
        verify { jdbc.update(match<String> { it.startsWith("UPDATE paper_accounts SET cash = cash +") }, match<BigDecimal> { it.compareTo(BigDecimal("3060")) == 0 }, 1L) }
    }

    @Test
    fun `does nothing when the price has not crossed`() {
        val o = order(OrderSide.BUY, "1000")
        stubLock(true); stubPrice("1001")
        every { orderRepo.findById(7L) } returns Optional.of(o)

        assertThat(filler.fillIfCrossed(7L)).isEqualTo(LimitFillOutcome.NOT_CROSSED)
        assertThat(o.status).isEqualTo(OrderStatus.PENDING)
        verify(exactly = 0) { fillRepo.save(any()) }
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
    }

    // 다른 pod가 같은 행을 잡고 있거나, 사용자가 방금 취소했다 — 기다리지도, 두 번 체결하지도 않는다.
    @Test
    fun `skips when the row lock is not acquired`() {
        stubLock(false)
        assertThat(filler.fillIfCrossed(7L)).isEqualTo(LimitFillOutcome.SKIPPED)
        verify(exactly = 0) { orderRepo.findById(any()) }
        verify(exactly = 0) { fillRepo.save(any()) }
    }

    @Test
    fun `skips an order that is no longer open after the lock`() {
        stubLock(true)
        every { orderRepo.findById(7L) } returns Optional.of(order(OrderSide.BUY, "1000", status = OrderStatus.CANCELLED))
        assertThat(filler.fillIfCrossed(7L)).isEqualTo(LimitFillOutcome.SKIPPED)
        verify(exactly = 0) { fillRepo.save(any()) }
    }

    // 공매도를 만들지 않는다(ADR-047 §5) — 포지션이 사라졌으면 체결 대신 취소.
    @Test
    fun `cancels a crossed SELL when the position is gone instead of short selling`() {
        val o = order(OrderSide.SELL, "1000", qty = 5)
        stubLock(true); stubPrice("1100")
        every { orderRepo.findById(7L) } returns Optional.of(o)
        every { orderRepo.save(any()) } answers { firstArg() }
        every { jdbc.query(match<String> { it.contains("FROM portfolio_positions") }, any<RowMapper<Int>>(), 1L, 100L) } returns emptyList()

        assertThat(filler.fillIfCrossed(7L)).isEqualTo(LimitFillOutcome.REJECTED)
        assertThat(o.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(o.rejectReason).contains("보유 수량 부족")
        verify(exactly = 0) { fillRepo.save(any()) }
        verify { events.publishEvent(match<Any> { it is OrderCancelledEvent && it.refundAmount.signum() == 0 }) }
    }

    // ADR-074 Note — 미체결 지정가 매수는 체결 시점에 리스크 게이트를 다시 통과해야 한다. 막히면 취소 + 예약금 전액 환불.
    @Test
    fun `re-checks risk for a crossed BUY at the fill price with the order excluded`() {
        val o = order(OrderSide.BUY, "1000")
        stubLock(true); stubPrice("950"); stubSaves()
        every { orderRepo.findById(7L) } returns Optional.of(o)

        filler.fillIfCrossed(7L)

        verify { riskChecker.checkPaperFill(1L, 100L, 10, match { it.compareTo(BigDecimal("950")) == 0 }, 7L) }
    }

    @Test
    fun `cancels a crossed BUY and refunds the whole reservation when the fill-time risk check blocks`() {
        val o = order(OrderSide.BUY, "1000")
        stubLock(true); stubPrice("950")
        every { orderRepo.findById(7L) } returns Optional.of(o)
        every { orderRepo.save(any()) } answers { firstArg() }
        every { riskChecker.checkPaperFill(any(), any(), any(), any(), any()) } returns
            RiskCheckResult(approved = false, blockedBy = "ConcentrationRule", severity = "BLOCKED", checks = emptyList())

        assertThat(filler.fillIfCrossed(7L)).isEqualTo(LimitFillOutcome.REJECTED)

        assertThat(o.status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(o.rejectReason).contains("ConcentrationRule")
        verify(exactly = 0) { fillRepo.save(any()) }
        // 예약금 1000×10 = 10,000 전액 환불
        verify { jdbc.update(match<String> { it.startsWith("UPDATE paper_accounts SET cash = cash +") }, match<BigDecimal> { it.compareTo(BigDecimal("10000")) == 0 }, 1L) }
        verify { events.publishEvent(match<Any> { it is OrderCancelledEvent && it.refundAmount.compareTo(BigDecimal("10000")) == 0 }) }
        verify(exactly = 0) { events.publishEvent(match<Any> { it is OrderFilledEvent }) }
    }

    @Test
    fun `does not risk-check SELL fills`() {
        val o = order(OrderSide.SELL, "1000", qty = 3)
        stubLock(true); stubPrice("1020"); stubSaves()
        every { orderRepo.findById(7L) } returns Optional.of(o)
        every { jdbc.query(match<String> { it.contains("FROM portfolio_positions") }, any<RowMapper<Int>>(), 1L, 100L) } returns listOf(3)

        filler.fillIfCrossed(7L)

        verify(exactly = 0) { riskChecker.checkPaperFill(any(), any(), any(), any(), any()) }
    }

    // 보안 리뷰 — 시세가 끊긴 뒤의 마지막 1분봉(몇 시간·며칠 전 값)으로 체결하지 않는다. 이번 주기를 건너뛴다.
    @Test
    fun `does not fill on a stale candle`() {
        val o = order(OrderSide.BUY, "1000")
        stubLock(true); stubPrice("900", at = java.time.Instant.now().minus(CandleFreshness.MAX_AGE).minusSeconds(60))
        every { orderRepo.findById(7L) } returns Optional.of(o)

        assertThat(filler.fillIfCrossed(7L)).isEqualTo(LimitFillOutcome.NOT_CROSSED)
        assertThat(o.status).isEqualTo(OrderStatus.PENDING)
        verify(exactly = 0) { fillRepo.save(any()) }
    }

    @Test
    fun `sweep candidates ignore stale candles`() {
        assertThat(LimitOrderSweeper.CANDIDATES_SQL).contains("candle_time >= now() - interval '${CandleFreshness.MAX_AGE_SQL}'")
    }
}
