package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageOrder
import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import com.monticker.api.brokerage.domain.OrderSide
import com.monticker.api.brokerage.domain.OrderType
import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import com.monticker.api.marketdata.domain.PriceSource
import com.monticker.api.marketdata.domain.PriceTick
import com.monticker.api.marketdata.domain.TickProvenance
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
    private val registry = SimpleMeterRegistry()
    private val haltService = mockk<TradingHaltService> { every { findActive(any(), any()) } returns null }
    private val realClient = mockk<com.monticker.api.brokerage.infrastructure.BrokerageClient> { every { movesRealMoney } returns true }
    private val realRegistry = com.monticker.api.brokerage.infrastructure.BrokerageClientRegistry(
        com.monticker.api.brokerage.domain.BrokerageProvider.entries.associateWith { realClient })
    private val failures = mockk<ConditionalOrderFailures> { every { markFailed(any(), any(), any()) } returns true }
    private val evaluator = ConditionalOrderEvaluator(jdbc, brokerageService, registry, haltService, realRegistry, failures)

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
                every { getString("provider") } returns "KIS"
            }
            listOf(mapper.mapRow(rs, 0))
        }
    }

    private fun tick(
        stockId: Long, price: String,
        source: PriceSource = PriceSource.KIS, marketStatus: String? = "OPEN", generatedAt: Instant = Instant.now(),
    ) = MarketTickReceivedEvent(
        PriceTick(stockId, "005930", BigDecimal(price), 10L, Instant.now()),
        TickProvenance(source, marketStatus, generatedAt),
    )

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
        every { brokerageService.submitOrder(1L, capture(orderSlot), any()) } returns makeOrder(100L, BrokerageOrderStatus.FILLED)

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

        verify(exactly = 0) { brokerageService.submitOrder(any(), any(), any()) }
    }

    @Test
    fun `이미 다른 스레드가 가져간 조건은 중복 발동하지 않는다`() {
        stubActiveRow()
        // 영향받은 행 0 -> 다른 스레드/이전 이벤트가 이미 TRIGGERED로 바꿔둔 상태
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 0

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify(exactly = 0) { brokerageService.submitOrder(any(), any(), any()) }
    }

    @Test
    fun `증권사가 거부하면 FAILED로 기록하고 OCO 형제를 취소한다`() {
        val groupId = UUID.randomUUID()
        stubActiveRow(ocoGroupId = groupId.toString())
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { jdbc.update(match<String> { it.contains("oco_group_id = ?") }, *anyVararg()) } returns 1
        every { brokerageService.submitOrder(1L, any(), any()) } returns makeOrder(100L, BrokerageOrderStatus.REJECTED, "리스크 한도 초과")

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify { failures.markFailed(1L, "증권사가 주문을 거부했습니다(리스크 한도 초과)", 100L) }
        verify { jdbc.update(match<String> { it.contains("oco_group_id = ?") }, any(), groupId, 1L) }
    }

    @Test
    fun `브로커 호출 자체가 예외를 던지면 FAILED로 기록하고 executed_order_id는 null이다`() {
        stubActiveRow()
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { brokerageService.submitOrder(1L, any(), any()) } throws IllegalStateException("리스크 게이트 거부")

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify { failures.markFailed(1L, "리스크 게이트 거부", null) }
    }

    @Test
    fun `결과 불명 주문 중복 가드에 막히면 FAILED로 닫고 막은 주문 번호를 사유에 남긴다`() {
        stubActiveRow()
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { brokerageService.submitOrder(1L, any(), any()) } throws UnresolvedOrderInProgressException("005930", "SELL", 31L)

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify { failures.markFailed(1L, "증권사 확인 중인 같은 방향 주문(#31)이 있어 새 주문을 낼 수 없었습니다", null) }
    }

    // ── ADR-056 — 결과 불명 ──────────────────────────────────────────────────────

    @Test
    fun `조건부 주문 하나당 결정적 clientOrderId(co-id)로 제출한다 — 리퍼가 이 값으로 주문 행을 찾는다`() {
        stubActiveRow(id = 42L)
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { jdbc.update(match<String> { it.contains("SET status = 'EXECUTED'") }, *anyVararg()) } returns 1
        every { brokerageService.submitOrder(1L, any(), "co-42") } returns makeOrder(100L, BrokerageOrderStatus.FILLED)

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify(exactly = 1) { brokerageService.submitOrder(1L, any(), "co-42") }
    }

    @Test
    fun `주문 결과가 불명이면 FAILED·EXECUTED로 단정하지 않고 TRIGGERED로 둔 채 주문만 연결한다`() {
        stubActiveRow()
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { jdbc.update(match<String> { it.contains("SET executed_order_id = ?") }, *anyVararg()) } returns 1
        every { brokerageService.submitOrder(1L, any(), any()) } returns makeOrder(100L, BrokerageOrderStatus.UNKNOWN)

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify { jdbc.update(match<String> { it.contains("SET executed_order_id = ?") }, 100L, any(), 1L) }
        verify(exactly = 0) { jdbc.update(match<String> { it.contains("SET status = 'EXECUTED'") }, *anyVararg()) }
        verify(exactly = 0) { failures.markFailed(any(), any(), any()) }
    }

    @Test
    fun `결과 기록이 실패해 OrderOutcomeUnknownException이 오면 역시 FAILED로 단정하지 않는다`() {
        stubActiveRow()
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { jdbc.update(match<String> { it.contains("SET executed_order_id = ?") }, *anyVararg()) } returns 1
        every { brokerageService.submitOrder(1L, any(), any()) } throws OrderOutcomeUnknownException(77L, RuntimeException("db down"))

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify { jdbc.update(match<String> { it.contains("SET executed_order_id = ?") }, 77L, any(), 1L) }
        verify(exactly = 0) { failures.markFailed(any(), any(), any()) }
    }

    // ── ADR-055 — 시세 출처 게이트 ──────────────────────────────────────────────

    @Test
    fun `합성(MOCK) 틱은 트리거 가격을 넘어도 실주문을 내지 않고 DB도 조회하지 않는다`() {
        stubActiveRow()

        evaluator.onTick(tick(stockId = 1L, price = "1", source = PriceSource.MOCK))

        verify(exactly = 0) { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
        verify(exactly = 0) { brokerageService.submitOrder(any(), any(), any()) }
        assertThat(registry.counter("conditional_order_tick_ignored_total", "reason", "source").count()).isEqualTo(1.0)
    }

    @Test
    fun `출처를 모르는 틱은 실시세가 아닌 것으로 취급한다`() {
        stubActiveRow()

        evaluator.onTick(tick(stockId = 1L, price = "1", source = PriceSource.UNKNOWN))

        verify(exactly = 0) { brokerageService.submitOrder(any(), any(), any()) }
    }

    @Test
    fun `실시세라도 정규장이 아니면 발동하지 않는다`() {
        stubActiveRow()

        evaluator.onTick(tick(stockId = 1L, price = "1", marketStatus = "POST_MARKET"))
        evaluator.onTick(tick(stockId = 1L, price = "1", marketStatus = null))

        verify(exactly = 0) { brokerageService.submitOrder(any(), any(), any()) }
        assertThat(registry.counter("conditional_order_tick_ignored_total", "reason", "marketStatus").count()).isEqualTo(2.0)
    }

    @Test
    fun `파이프라인 랙 SLO(5초)를 넘겨 도착한 틱으로는 발동하지 않는다`() {
        stubActiveRow()

        evaluator.onTick(tick(stockId = 1L, price = "1", generatedAt = Instant.now().minusSeconds(30)))

        verify(exactly = 0) { brokerageService.submitOrder(any(), any(), any()) }
        assertThat(registry.counter("conditional_order_tick_ignored_total", "reason", "stale").count()).isEqualTo(1.0)
    }

    @Test
    fun `Toss 실시세도 실시세다`() {
        stubActiveRow()
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { jdbc.update(match<String> { it.contains("SET status = 'EXECUTED'") }, *anyVararg()) } returns 1
        every { brokerageService.submitOrder(1L, any(), any()) } returns makeOrder(100L, BrokerageOrderStatus.FILLED)

        evaluator.onTick(tick(stockId = 1L, price = "69000", source = PriceSource.TOSS))

        verify(exactly = 1) { brokerageService.submitOrder(1L, any(), any()) }
    }

    // ── ADR-057 — 킬 스위치 ──────────────────────────────────────────────────────

    private val activeHalt = TradingHalt(1, HaltScope.PROVIDER, "KIS", "점검", 9, Instant.now(), null, null, null)

    @Test
    fun `스위치가 켜져 있으면 조건을 만족해도 클레임하지 않는다 — ACTIVE로 남아 해제 후 재개`() {
        stubActiveRow()
        every { haltService.findActive(com.monticker.api.brokerage.domain.BrokerageProvider.KIS, 1L) } returns activeHalt

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify(exactly = 0) { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) }
        verify(exactly = 0) { brokerageService.submitOrder(any(), any(), any()) }
        assertThat(registry.counter("conditional_order_halted_total").count()).isEqualTo(1.0)
    }

    @Test
    fun `평가기 확인 뒤 스위치가 켜져 주문 준비가 막히면 TRIGGERED를 ACTIVE로 되돌린다 — FAILED로 소모하지 않는다`() {
        val groupId = UUID.randomUUID()
        stubActiveRow(ocoGroupId = groupId.toString())
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { jdbc.update(match<String> { it.contains("SET status = 'ACTIVE'") }, *anyVararg()) } returns 1
        every { brokerageService.submitOrder(1L, any(), any()) } throws
            com.monticker.api.common.exception.TradingHaltedException("실거래 주문이 일시 중단되었습니다", "GLOBAL")

        evaluator.onTick(tick(stockId = 1L, price = "69000"))

        verify { jdbc.update(match<String> { it.contains("SET status = 'ACTIVE', triggered_at = NULL") }, any(), 1L) }
        verify(exactly = 0) { failures.markFailed(any(), any(), any()) }
        verify(exactly = 0) { jdbc.update(match<String> { it.contains("oco_group_id = ?") }, *anyVararg()) }   // OCO 형제도 그대로
    }

    // ── ADR-060 — Mock 증권사 계좌는 합성 시세로도 발동 ─────────────────────────────────

    @Test
    fun `실제 돈을 움직이지 않는 증권사(Mock 모드)면 합성 틱으로도 발동한다`() {
        val mockClient = mockk<com.monticker.api.brokerage.infrastructure.BrokerageClient> { every { movesRealMoney } returns false }
        val mockEvaluator = ConditionalOrderEvaluator(jdbc, brokerageService, registry, haltService,
            com.monticker.api.brokerage.infrastructure.BrokerageClientRegistry(
                com.monticker.api.brokerage.domain.BrokerageProvider.entries.associateWith { mockClient }), failures)
        stubActiveRow()
        every { jdbc.update(match<String> { it.contains("SET status = 'TRIGGERED'") }, *anyVararg()) } returns 1
        every { jdbc.update(match<String> { it.contains("SET status = 'EXECUTED'") }, *anyVararg()) } returns 1
        every { brokerageService.submitOrder(1L, any(), any()) } returns makeOrder(100L, BrokerageOrderStatus.FILLED)

        mockEvaluator.onTick(tick(stockId = 1L, price = "69000", source = PriceSource.MOCK, marketStatus = "POST_MARKET"))

        verify(exactly = 1) { brokerageService.submitOrder(1L, any(), any()) }
    }
}
