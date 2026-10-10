package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.*
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.infrastructure.*
import com.monticker.api.common.exception.BusinessRuleException
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

class RebalanceExecutionServiceTest {

    private val targetService = mockk<RebalanceTargetService>()
    private val brokerageService = mockk<BrokerageService>()
    private val executionRepo = mockk<RebalanceExecutionRepository>()
    private val legRepo = mockk<RebalanceExecutionLegRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val service = RebalanceExecutionService(targetService, brokerageService, executionRepo, legRepo, jdbc, mockk { every { findActive(any(), any()) } returns null })

    init {
        // ADR-057 — execute()가 킬 스위치를 보려고 계좌의 증권사를 읽는다.
        every { brokerageService.getAccount(1L) } returns
            com.monticker.api.brokerage.domain.BrokerageAccount(userId = 1L, provider = com.monticker.api.brokerage.domain.BrokerageProvider.KIS, accountNumber = "12345678")
    }

    private fun makeTarget(weights: Map<String, BigDecimal>, thresholdPct: BigDecimal = BigDecimal("5.00")) =
        RebalanceTarget(id = 1L, userId = 1L, accountId = 1L, weightsJson = "{}", thresholdPct = thresholdPct, source = RebalanceTargetSource.MANUAL).also {
            every { targetService.parseWeights(it) } returns weights
        }

    private fun makeBalance(totalEvaluated: BigDecimal, holdings: List<BrokerageHolding>, cash: BigDecimal = BigDecimal.ZERO) =
        BrokerageBalance(cash = cash, totalEvaluated = totalEvaluated, holdings = holdings)

    private fun stubStockId(symbol: String, id: Long) {
        every { jdbc.queryForObject(STOCK_ID_BY_SYMBOL_SQL, Long::class.java, symbol) } returns id
    }

    private fun makeOrder(id: Long, status: BrokerageOrderStatus, rejectReason: String? = null) =
        BrokerageOrder(id = id, userId = 1L, accountId = 1L, symbol = "X", side = OrderSide.BUY, orderType = OrderType.MARKET, quantity = 1, status = status, rejectReason = rejectReason)

    // ── preview ──────────────────────────────────────────────────────────────

    @Test
    fun `저장된 목표가 없으면 preview는 실패한다`() {
        every { targetService.get(1L) } returns null
        assertThatThrownBy { service.preview(1L) }.isInstanceOf(BusinessRuleException::class.java)
    }

    @Test
    fun `임계값 미만 괴리는 leg에서 제외된다`() {
        val target = makeTarget(mapOf("005930" to BigDecimal("0.30")), thresholdPct = BigDecimal("5.00"))
        every { targetService.get(1L) } returns target
        // 목표 30% vs 현재 28% -> 괴리 2%p, 임계값 5% 미만 -> 제외
        val holding = BrokerageHolding(symbol = "005930", quantity = 28, avgPrice = BigDecimal("70000"), currentPrice = BigDecimal("70000"))
        every { brokerageService.getBalance(1L) } returns makeBalance(BigDecimal("7000000"), listOf(holding))

        val preview = service.preview(1L)

        assertThat(preview.legs).isEmpty()
    }

    @Test
    fun `보유하지 않은 종목이 목표에 있으면 BUY leg가 생성된다`() {
        val target = makeTarget(mapOf("005930" to BigDecimal("0.50")))
        every { targetService.get(1L) } returns target
        every { brokerageService.getBalance(1L) } returns makeBalance(BigDecimal("1000000"), emptyList())
        every { jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), eq("005930")) } returns BigDecimal("50000")
        stubStockId("005930", 2L)

        val preview = service.preview(1L)

        assertThat(preview.legs).hasSize(1)
        val leg = preview.legs.first()
        assertThat(leg.side).isEqualTo(OrderSide.BUY)
        assertThat(leg.symbol).isEqualTo("005930")
        // 목표 50% of 1,000,000 = 500,000 / 가격 50,000 = 10주
        assertThat(leg.quantity).isEqualTo(10)
    }

    @Test
    fun `preview는 leg별 추정 가격·금액과 정산과 같은 식의 예상 거래비용을 준다`() {
        // 보유 A(잔고 현재가) 매도 + 미보유 B(최근 1분봉) 신규 매수
        val target = makeTarget(mapOf("000660" to BigDecimal("0.50")))
        every { targetService.get(1L) } returns target
        val holdingA = BrokerageHolding(symbol = "005930", quantity = 20, avgPrice = BigDecimal("60000"), currentPrice = BigDecimal("50000"))
        every { brokerageService.getBalance(1L) } returns makeBalance(BigDecimal("1000000"), listOf(holdingA))
        every { jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), eq("000660")) } returns BigDecimal("100000")
        stubStockId("005930", 1L)
        stubStockId("000660", 2L)

        val preview = service.preview(1L)

        val sell = preview.legs.single { it.side == OrderSide.SELL }
        assertThat(sell.price).isEqualByComparingTo("50000")
        assertThat(sell.priceSource).isEqualTo(LegPriceSource.BROKER_BALANCE)
        assertThat(sell.quantity).isEqualTo(20)
        assertThat(sell.estimatedAmount).isEqualByComparingTo("1000000")
        assertThat(sell.estimatedFee).isEqualByComparingTo("150")      // 0.015%
        assertThat(sell.estimatedTax).isEqualByComparingTo("1800")     // 0.18%

        val buy = preview.legs.single { it.side == OrderSide.BUY }
        assertThat(buy.priceSource).isEqualTo(LegPriceSource.LAST_CANDLE)
        assertThat(buy.quantity).isEqualTo(5)                          // 50% of 1,000,000 / 100,000
        assertThat(buy.estimatedAmount).isEqualByComparingTo("500000")
        assertThat(buy.estimatedFee).isEqualByComparingTo("75")
        assertThat(buy.estimatedTax).isEqualByComparingTo("0")

        assertThat(preview.estimatedCost).isEqualByComparingTo("2025")
        assertThat(preview.estimatedBuyAmount).isEqualByComparingTo("500000")
        assertThat(preview.estimatedNewBuyAmount).isEqualByComparingTo("500000")
        assertThat(preview.estimatedSellAmount).isEqualByComparingTo("1000000")
    }

    @Test
    fun `preview는 주문·실행 기록을 만들지 않는다`() {
        val target = makeTarget(mapOf("005930" to BigDecimal("0.50")))
        every { targetService.get(1L) } returns target
        every { brokerageService.getBalance(1L) } returns makeBalance(BigDecimal("1000000"), emptyList())
        every { jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), eq("005930")) } returns BigDecimal("50000")
        stubStockId("005930", 2L)

        service.preview(1L)

        verify(exactly = 0) { brokerageService.submitOrder(any(), any(), any()) }
        verify(exactly = 0) { executionRepo.save(any()) }
        verify(exactly = 0) { legRepo.save(any()) }
    }

    @Test
    fun `수량이 Int 범위를 넘으면 그 leg는 버려진다`() {
        // V-L5 — BigDecimal.toInt()는 예외 없이 32비트로 wrap한다. diffPct*totalValue/price가
        // Int.MAX_VALUE(약 21억)를 넘으면 엉뚱한 수량으로 이어질 수 있어 leg 자체를 버려야 한다.
        val target = makeTarget(mapOf("005930" to BigDecimal("1.00")))
        every { targetService.get(1L) } returns target
        every { brokerageService.getBalance(1L) } returns makeBalance(BigDecimal("5000000000000"), emptyList())
        every { jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), eq("005930")) } returns BigDecimal("1")
        stubStockId("005930", 2L)

        val preview = service.preview(1L)

        assertThat(preview.legs).isEmpty()
    }

    @Test
    fun `목표에 없는 보유 종목은 전량 매도 후보가 되고 보유 수량을 넘지 않는다`() {
        val target = makeTarget(emptyMap())
        every { targetService.get(1L) } returns target
        val holding = BrokerageHolding(symbol = "005930", quantity = 7, avgPrice = BigDecimal("70000"), currentPrice = BigDecimal("70000"))
        every { brokerageService.getBalance(1L) } returns makeBalance(BigDecimal("490000"), listOf(holding))
        stubStockId("005930", 2L)

        val preview = service.preview(1L)

        assertThat(preview.legs).hasSize(1)
        val leg = preview.legs.first()
        assertThat(leg.side).isEqualTo(OrderSide.SELL)
        assertThat(leg.quantity).isEqualTo(7)  // 100% 괴리라도 보유 수량(7주) 이상 팔 수 없음
    }

    // ── execute ──────────────────────────────────────────────────────────────

    @Test
    fun `실행 대상이 없으면 execute는 실패한다`() {
        val target = makeTarget(emptyMap())
        every { targetService.get(1L) } returns target
        every { brokerageService.getBalance(1L) } returns makeBalance(BigDecimal.ZERO, emptyList())

        assertThatThrownBy { service.execute(1L) }.isInstanceOf(BusinessRuleException::class.java)
    }

    @Test
    fun `매도가 매수보다 먼저 실행되고 각 leg는 실제 주문으로 기록된다`() {
        val target = makeTarget(mapOf("005930" to BigDecimal("0.50")))
        every { targetService.get(1L) } returns target
        // 000660 보유(목표 없음, 전량 매도 대상) + 005930 신규 매수 대상
        val holding = BrokerageHolding(symbol = "000660", quantity = 5, avgPrice = BigDecimal("100000"), currentPrice = BigDecimal("100000"))
        every { brokerageService.getBalance(1L) } returns makeBalance(BigDecimal("1000000"), listOf(holding))
        every { jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), eq("005930")) } returns BigDecimal("50000")
        stubStockId("005930", 2L)
        stubStockId("000660", 3L)

        every { executionRepo.save(any<RebalanceExecution>()) } answers { firstArg<RebalanceExecution>().let { if (it.id == 0L) RebalanceExecution(id = 100L, userId = it.userId, accountId = it.accountId, targetId = it.targetId, status = it.status, requestedAt = it.requestedAt, completedAt = it.completedAt) else it } }
        val legSlot = mutableListOf<RebalanceExecutionLeg>()
        every { legRepo.save(capture(legSlot)) } answers { firstArg() }

        val orderCallOrder = mutableListOf<String>()
        every { brokerageService.submitOrder(1L, any(), any()) } answers {
            val req = secondArg<BrokerageOrderRequest>()
            orderCallOrder += req.symbol
            makeOrder(id = if (req.symbol == "000660") 200L else 201L, status = BrokerageOrderStatus.FILLED)
        }

        val execution = service.execute(1L)

        assertThat(orderCallOrder).containsExactly("000660", "005930")  // SELL 먼저
        assertThat(execution.status).isEqualTo(RebalanceExecutionStatus.COMPLETED)
        assertThat(legSlot).hasSize(2)
        assertThat(legSlot.map { it.status }).allMatch { it == RebalanceLegStatus.EXECUTED }
    }

    @Test
    fun `한 leg가 거부돼도 나머지는 계속 실행되고 상태는 PARTIALLY_FAILED다`() {
        val target = makeTarget(mapOf("005930" to BigDecimal("0.50")))
        every { targetService.get(1L) } returns target
        val holding = BrokerageHolding(symbol = "000660", quantity = 5, avgPrice = BigDecimal("100000"), currentPrice = BigDecimal("100000"))
        every { brokerageService.getBalance(1L) } returns makeBalance(BigDecimal("1000000"), listOf(holding))
        every { jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), eq("005930")) } returns BigDecimal("50000")
        stubStockId("005930", 2L)
        stubStockId("000660", 3L)

        every { executionRepo.save(any<RebalanceExecution>()) } answers {
            firstArg<RebalanceExecution>().let { if (it.id == 0L) RebalanceExecution(id = 100L, userId = it.userId, accountId = it.accountId, targetId = it.targetId, status = it.status, requestedAt = it.requestedAt, completedAt = it.completedAt) else it }
        }
        val legSlot = mutableListOf<RebalanceExecutionLeg>()
        every { legRepo.save(capture(legSlot)) } answers { firstArg() }

        every { brokerageService.submitOrder(1L, match { it.symbol == "000660" }, any()) } returns makeOrder(200L, BrokerageOrderStatus.REJECTED, "리스크 한도 초과")
        every { brokerageService.submitOrder(1L, match { it.symbol == "005930" }, any()) } returns makeOrder(201L, BrokerageOrderStatus.FILLED)

        val execution = service.execute(1L)

        assertThat(execution.status).isEqualTo(RebalanceExecutionStatus.PARTIALLY_FAILED)
        assertThat(legSlot).hasSize(2)
        val sellLeg = legSlot.first { it.symbol == "000660" }
        assertThat(sellLeg.status).isEqualTo(RebalanceLegStatus.FAILED)
        assertThat(sellLeg.failReason).isEqualTo("리스크 한도 초과")
        val buyLeg = legSlot.first { it.symbol == "005930" }
        assertThat(buyLeg.status).isEqualTo(RebalanceLegStatus.EXECUTED)
    }

    @Test
    fun `결과 불명 leg는 FAILED가 아니라 UNKNOWN으로 남고 주문을 가리킨다 — 실제로 체결됐을 수 있다`() {
        val target = makeTarget(mapOf("005930" to BigDecimal("0.50")))
        every { targetService.get(1L) } returns target
        every { brokerageService.getBalance(1L) } returns makeBalance(BigDecimal("1000000"), emptyList())
        every { jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), eq("005930")) } returns BigDecimal("50000")
        stubStockId("005930", 2L)
        every { executionRepo.save(any<RebalanceExecution>()) } answers { firstArg() }
        val legSlot = mutableListOf<RebalanceExecutionLeg>()
        every { legRepo.save(capture(legSlot)) } answers { firstArg() }
        every { brokerageService.submitOrder(1L, any(), any()) } throws OrderOutcomeUnknownException(301L, RuntimeException("db down"))

        service.execute(1L)

        assertThat(legSlot.single().status).isEqualTo(RebalanceLegStatus.UNKNOWN)
        assertThat(legSlot.single().executedOrderId).isEqualTo(301L)
    }

    @Test
    fun `킬 스위치가 켜져 있으면 실행 기록을 만들지 않고 막는다`() {
        val halted = RebalanceExecutionService(targetService, brokerageService, executionRepo, legRepo, jdbc,
            mockk { every { findActive(any(), any()) } returns TradingHalt(1, HaltScope.USER, "1", "조사", 9, java.time.Instant.now(), null, null, null) })
        every { targetService.get(1L) } returns makeTarget(mapOf("005930" to BigDecimal("0.50")))

        org.assertj.core.api.Assertions.assertThatThrownBy { halted.execute(1L) }
            .isInstanceOf(com.monticker.api.common.exception.TradingHaltedException::class.java)
        verify(exactly = 0) { executionRepo.save(any()) }
        verify(exactly = 0) { brokerageService.submitOrder(any(), any(), any()) }
    }

    @Test
    fun `실행 도중 킬 스위치가 켜지면 남은 leg는 시도하지 않고 미전송으로 남긴다`() {
        val target = makeTarget(mapOf("005930" to BigDecimal("0.50")))
        every { targetService.get(1L) } returns target
        val holding = BrokerageHolding(symbol = "000660", quantity = 5, avgPrice = BigDecimal("100000"), currentPrice = BigDecimal("100000"))
        every { brokerageService.getBalance(1L) } returns makeBalance(BigDecimal("1000000"), listOf(holding))
        every { jdbc.queryForObject(match<String> { it.contains("candles_1m") }, eq(BigDecimal::class.java), eq("005930")) } returns BigDecimal("50000")
        stubStockId("005930", 2L)
        stubStockId("000660", 3L)
        every { executionRepo.save(any<RebalanceExecution>()) } answers { firstArg() }
        val legSlot = mutableListOf<RebalanceExecutionLeg>()
        every { legRepo.save(capture(legSlot)) } answers { firstArg() }
        // 매도(000660)가 먼저 나가는 중에 스위치가 켜졌다
        every { brokerageService.submitOrder(1L, any(), any()) } throws
            com.monticker.api.common.exception.TradingHaltedException("실거래 주문이 일시 중단되었습니다", "GLOBAL")

        val execution = service.execute(1L)

        verify(exactly = 1) { brokerageService.submitOrder(1L, any(), any()) }   // 매수는 시도조차 하지 않는다
        assertThat(legSlot.map { it.symbol }).containsExactly("000660", "005930")
        assertThat(legSlot).allMatch { it.status == RebalanceLegStatus.FAILED && it.failReason!!.contains("킬 스위치") }
        assertThat(execution.status).isEqualTo(RebalanceExecutionStatus.PARTIALLY_FAILED)
    }
}
