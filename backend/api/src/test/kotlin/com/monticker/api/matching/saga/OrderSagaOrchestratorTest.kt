package com.monticker.api.matching.saga

import com.monticker.api.matching.submit.OrderOrigin
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
import com.monticker.api.common.domain.CandleFreshness
import com.monticker.api.common.domain.LatestClose
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

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

    private fun stubStockExistsAndPrice(candleTime: Instant? = Instant.now()) {
        every { jdbc.queryForObject("SELECT COUNT(*) FROM stocks WHERE id = ?", Long::class.java, stockId) } returns 1L
        // latestClose는 queryForObject가 아니라 query+firstOrNull을 쓴다(0건일 때
        // EmptyResultDataAccessException을 던지지 않고 그냥 빈 리스트를 받기 위함).
        every {
            jdbc.query(
                OrderSagaOrchestrator.LATEST_PRICE_SQL,
                any<org.springframework.jdbc.core.RowMapper<LatestClose>>(), stockId,
            )
        } returns listOfNotNull(candleTime?.let { LatestClose(currentPrice, it) })
    }

    /**
     * reserveCash()는 이제 "확인 후 차감"이 아니라 `UPDATE ... WHERE cash >= ?` 하나로
     * 원자화되어 있으므로(동시성 레이스 방지), 테스트도 그 UPDATE의 반환 행 수(성공 시 1,
     * 잔고 부족 시 0)를 스텁한다 — 더 이상 SELECT cash를 직접 스텁하지 않는다.
     */
    private fun stubAccountCash(sufficient: Boolean = true) {
        every {
            jdbc.update(
                match<String> { it.startsWith("UPDATE paper_accounts SET cash = cash -") },
                any<BigDecimal>(), userId, any<BigDecimal>(),
            )
        } returns if (sufficient) 1 else 0
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
            SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10, origin = OrderOrigin.MANUAL),
        )

        assertThat(response.order.status).isEqualTo("FILLED")
        assertThat(response.fills).hasSize(1)
        assertThat(response.fills[0].fillPrice).isEqualByComparingTo(currentPrice)
        verify { fillRepo.save(any()) }
        verify { eventPublisher.publishEvent(any<com.monticker.api.matching.events.OrderFilledEvent>()) }
    }

    // ADR-085 — 진입 출처는 주문 행과 체결 이벤트 양쪽에 남는다(체결 기록은 이벤트에서, 나중 스위퍼 체결은 주문 행에서 읽는다)
    @Test
    fun `execute stores the origin on the order row and carries it on the fill event`() {
        stubStockExistsAndPrice()
        stubAccountCash()
        val savedOrders = mutableListOf<com.monticker.api.matching.domain.Order>()
        every { orderRepo.save(capture(savedOrders)) } answers { savedOrders.last() }
        every { fillRepo.save(any()) } answers { firstArg() }
        val event = slot<com.monticker.api.matching.events.OrderFilledEvent>()
        every { eventPublisher.publishEvent(capture(event)) } returns Unit

        orchestrator.execute(
            userId,
            SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10, origin = OrderOrigin.watchRule(42L)),
        )

        assertThat(savedOrders.first().origin).isEqualTo(com.monticker.api.matching.submit.OrderOriginType.WATCH_RULE)
        assertThat(savedOrders.first().originRef).isEqualTo(42L)
        assertThat(event.captured.origin).isEqualTo("WATCH_RULE")
        assertThat(event.captured.originRef).isEqualTo(42L)
    }

    // ADR-091 — 접수 시점 최우선 호가와 접수 시각을 주문 행에 남긴다(슬리피지·엔진 지연의 근거)
    @Test
    fun `execute records the best quote and the submit time on the order row`() {
        stubStockExistsAndPrice()
        stubAccountCash()
        val savedOrders = mutableListOf<com.monticker.api.matching.domain.Order>()
        every { orderRepo.save(capture(savedOrders)) } answers { savedOrders.last() }
        every { fillRepo.save(any()) } answers { firstArg() }
        val quotedAt = Instant.now().minusSeconds(1)
        val withQuote = OrderSagaOrchestrator(
            sagaRepo, orderRepo, fillRepo, fillQueryService, orderBookService, stateMachineService, eventPublisher, jdbc,
            bestQuoteSource = { com.monticker.api.common.domain.BestQuote(BigDecimal("995"), BigDecimal("1005"), quotedAt, "KIS_REALTIME") },
        )
        val before = Instant.now()

        withQuote.execute(userId, SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 1, origin = OrderOrigin.MANUAL))

        val o = savedOrders.first()
        assertThat(o.quoteBid).isEqualByComparingTo("995")
        assertThat(o.quoteAsk).isEqualByComparingTo("1005")
        assertThat(o.quoteAt).isEqualTo(quotedAt)
        assertThat(o.quoteSource).isEqualTo("KIS_REALTIME")
        assertThat(o.submittedAt).isNotNull().isBetween(before, Instant.now())
    }

    @Test
    fun `a failing quote source does not block the order - the quote is simply not recorded`() {
        stubStockExistsAndPrice()
        stubAccountCash()
        val savedOrders = mutableListOf<com.monticker.api.matching.domain.Order>()
        every { orderRepo.save(capture(savedOrders)) } answers { savedOrders.last() }
        every { fillRepo.save(any()) } answers { firstArg() }
        val failing = OrderSagaOrchestrator(
            sagaRepo, orderRepo, fillRepo, fillQueryService, orderBookService, stateMachineService, eventPublisher, jdbc,
            bestQuoteSource = { throw IllegalStateException("redis down") },
        )

        val res = failing.execute(userId, SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 1, origin = OrderOrigin.MANUAL))

        assertThat(res.order.status).isEqualTo("FILLED")
        assertThat(savedOrders.first().quoteBid).isNull()
        assertThat(savedOrders.first().quoteAsk).isNull()
        assertThat(savedOrders.first().submittedAt).isNotNull()
    }

    @Test
    fun `execute leaves a LIMIT order unfilled and submits it to the order book when price does not cross`() {
        stubStockExistsAndPrice()
        stubAccountCash()
        stubOrderAndFillSaves()
        val limitPrice = BigDecimal("900")

        val response = orchestrator.execute(
            userId,
            SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "LIMIT", quantity = 10, limitPrice = limitPrice, origin = OrderOrigin.MANUAL),
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
            SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10, origin = OrderOrigin.MANUAL),
        )

        assertThat(response.order.status).isEqualTo("FILLED")
        verify(exactly = 0) { orderBookService.submit(any()) }
    }

    @Test
    fun `execute rejects a BUY order when cash is insufficient and runs compensation`() {
        stubStockExistsAndPrice()
        stubAccountCash(sufficient = false)

        org.assertj.core.api.Assertions.assertThatThrownBy {
            orchestrator.execute(
                userId,
                SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10, origin = OrderOrigin.MANUAL),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        // CASH_RESERVED 단계까지 못 갔으므로 (require 실패가 CASH_RESERVED 진입 이전) 주문/체결 저장은 없어야 함
        verify(exactly = 0) { orderRepo.save(any()) }
        verify(exactly = 0) { fillRepo.save(any()) }
    }

    // ADR-047 — 매도는 보유 수량 안에서만. 이전엔 이 확인이 구 페이퍼 경로에만 있어 매칭 엔진으로는 공매도가 됐다.
    @Test
    fun `execute rejects a SELL order beyond the held quantity before touching orders or cash`() {
        stubStockExistsAndPrice()
        every {
            jdbc.query(match<String> { it.contains("FROM portfolio_positions") }, any<org.springframework.jdbc.core.RowMapper<Int>>(), userId, stockId)
        } returns listOf(2)

        org.assertj.core.api.Assertions.assertThatThrownBy {
            orchestrator.execute(userId, SubmitOrderRequest(stockId = stockId, side = "SELL", orderType = "MARKET", quantity = 5, origin = OrderOrigin.MANUAL))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("보유 수량 부족")

        verify(exactly = 0) { orderRepo.save(any()) }
        verify(exactly = 0) { fillRepo.save(any()) }
        verify(exactly = 0) { jdbc.update(match<String> { it.startsWith("UPDATE paper_accounts") }, *anyVararg()) }
    }

    // ADR-074 — 미체결 SELL 지정가의 잔량은 이미 팔기로 한 수량이다. 보유 10, 미체결 매도 8이면 3주 매도는 거부.
    @Test
    fun `execute subtracts open SELL orders from the sellable quantity`() {
        stubStockExistsAndPrice()
        every {
            jdbc.query(match<String> { it.contains("FROM portfolio_positions") }, any<org.springframework.jdbc.core.RowMapper<Int>>(), userId, stockId)
        } returns listOf(10)
        every {
            jdbc.query(OrderSagaOrchestrator.PENDING_SELL_QTY_SQL, any<org.springframework.jdbc.core.RowMapper<Int>>(), userId, stockId)
        } returns listOf(8)

        org.assertj.core.api.Assertions.assertThatThrownBy {
            orchestrator.execute(userId, SubmitOrderRequest(stockId = stockId, side = "SELL", orderType = "LIMIT", quantity = 3, limitPrice = BigDecimal("1200"), origin = OrderOrigin.MANUAL))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("미체결 매도 8")

        verify(exactly = 0) { orderRepo.save(any()) }
    }

    @Test
    fun `execute throws a business IllegalStateException, not a raw DB exception, when the stock has no recent candle`() {
        // 부하 테스트로 실제 재현된 버그: query()가 0건일 때 queryForObject처럼
        // EmptyResultDataAccessException을 던지지 않고 빈 리스트를 반환하는지 확인한다 —
        // 그래야 "?: throw IllegalStateException"이 실제로 실행되어 GlobalExceptionHandler가
        // 이걸 안내 메시지 없는 500이 아니라 409로 분류할 수 있다.
        stubStockExistsAndPrice(candleTime = null)

        org.assertj.core.api.Assertions.assertThatThrownBy {
            orchestrator.execute(
                userId,
                SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10, origin = OrderOrigin.MANUAL),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("현재가")
    }

    // ── 시세 신선도(CandleFreshness) — 보안 리뷰 2026-10 ───────────────────────────────

    private val staleTime get() = Instant.now().minus(CandleFreshness.MAX_AGE).minus(Duration.ofMinutes(1))

    @Test
    fun `a MARKET order is rejected on a stale candle before any cash is reserved or order created`() {
        stubStockExistsAndPrice(candleTime = staleTime)
        stubAccountCash()

        org.assertj.core.api.Assertions.assertThatThrownBy {
            orchestrator.execute(userId, SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10, origin = OrderOrigin.MANUAL))
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("시장가 주문을 체결할 수 없습니다")

        // 예약 앞에서 거부 — 예약도 환불도 없다(현금은 정확히 0번 움직인다).
        verify(exactly = 0) { jdbc.update(match<String> { it.startsWith("UPDATE paper_accounts") }, *anyVararg()) }
        verify(exactly = 0) { orderRepo.save(any()) }
        verify(exactly = 0) { fillRepo.save(any()) }
    }

    @Test
    fun `a MARKET SELL is rejected on a stale candle without touching holdings or cash`() {
        stubStockExistsAndPrice(candleTime = staleTime)

        org.assertj.core.api.Assertions.assertThatThrownBy {
            orchestrator.execute(userId, SubmitOrderRequest(stockId = stockId, side = "SELL", orderType = "MARKET", quantity = 1, origin = OrderOrigin.MANUAL))
        }.isInstanceOf(IllegalStateException::class.java)

        verify(exactly = 0) { jdbc.query(match<String> { it.contains("FROM portfolio_positions") }, any<org.springframework.jdbc.core.RowMapper<Int>>(), *anyVararg()) }
        verify(exactly = 0) { jdbc.update(match<String> { it.startsWith("UPDATE paper_accounts") }, *anyVararg()) }
        verify(exactly = 0) { fillRepo.save(any()) }
    }

    @Test
    fun `a MARKET order fills on a candle just inside the freshness bound`() {
        stubStockExistsAndPrice(candleTime = Instant.now().minus(CandleFreshness.MAX_AGE).plusSeconds(30))
        stubAccountCash()
        stubOrderAndFillSaves()

        val res = orchestrator.execute(userId, SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "MARKET", quantity = 10, origin = OrderOrigin.MANUAL))

        assertThat(res.fills).hasSize(1)
        assertThat(res.fills.single().fillPrice).isEqualByComparingTo(currentPrice)
    }

    // 지정가는 거부하지 않는다 — 오래된 값으로 즉시 체결하지 않고 미체결로 접수해 스위퍼(같은 신선도 규칙)에 맡긴다.
    @Test
    fun `a crossing LIMIT order on a stale candle rests unfilled instead of filling at the stale price`() {
        stubStockExistsAndPrice(candleTime = staleTime)
        stubAccountCash()
        stubOrderAndFillSaves()

        val res = orchestrator.execute(
            userId, SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "LIMIT", quantity = 10, limitPrice = BigDecimal("1200"), origin = OrderOrigin.MANUAL),
        )

        assertThat(res.fills).isEmpty()
        assertThat(res.order.status).isEqualTo("PENDING")
        verify(exactly = 0) { fillRepo.save(any()) }
        verify { orderBookService.submit(any()) }
    }

    // ADR-096 — 예약 잠금 시각은 잠금이 성공한 뒤, 주문 행과 함께 저장된다(영수증 "예약금 잠금" 단계)
    @Test
    fun `a resting BUY LIMIT records reservedAt after the cash reservation succeeds`() {
        stubStockExistsAndPrice()
        stubAccountCash()
        val savedOrders = mutableListOf<com.monticker.api.matching.domain.Order>()
        every { orderRepo.save(capture(savedOrders)) } answers { savedOrders.last() }
        val before = Instant.now()

        orchestrator.execute(userId, SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "LIMIT", quantity = 3, limitPrice = BigDecimal("900"), origin = OrderOrigin.MANUAL))

        val o = savedOrders.first()
        assertThat(o.reservedAt).isNotNull().isBetween(before, Instant.now())
        assertThat(o.reservedAt).isAfterOrEqualTo(o.submittedAt)
    }

    @Test
    fun `a SELL LIMIT records reservedAt once the sellable quantity check passes`() {
        stubStockExistsAndPrice()
        every {
            jdbc.query(match<String> { it.contains("FROM portfolio_positions") }, any<org.springframework.jdbc.core.RowMapper<Int>>(), userId, stockId)
        } returns listOf(10)
        every {
            jdbc.query(OrderSagaOrchestrator.PENDING_SELL_QTY_SQL, any<org.springframework.jdbc.core.RowMapper<Int>>(), userId, stockId)
        } returns listOf(0)
        val savedOrders = mutableListOf<com.monticker.api.matching.domain.Order>()
        every { orderRepo.save(capture(savedOrders)) } answers { savedOrders.last() }

        orchestrator.execute(userId, SubmitOrderRequest(stockId = stockId, side = "SELL", orderType = "LIMIT", quantity = 3, limitPrice = BigDecimal("1200"), origin = OrderOrigin.MANUAL))

        assertThat(savedOrders.first().reservedAt).isNotNull()
    }

    @Test
    fun `a BUY with insufficient cash never creates an order row, so no reservedAt is left behind`() {
        stubStockExistsAndPrice()
        stubAccountCash(sufficient = false)

        org.assertj.core.api.Assertions.assertThatThrownBy {
            orchestrator.execute(userId, SubmitOrderRequest(stockId = stockId, side = "BUY", orderType = "LIMIT", quantity = 3, limitPrice = BigDecimal("900"), origin = OrderOrigin.MANUAL))
        }.isInstanceOf(IllegalArgumentException::class.java)

        verify(exactly = 0) { orderRepo.save(any()) }
    }
}
