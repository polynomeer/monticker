package com.monticker.api.brokerage.application

import io.mockk.verify
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional

class ConditionalOrderServiceTest {
    private val KST = java.time.ZoneId.of("Asia/Seoul")

    private val accountRepo = mockk<BrokerageAccountRepository>()
    private val conditionalOrderRepo = mockk<ConditionalOrderRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    // 기본은 실거래 모드(실제 돈을 움직이는 클라이언트) + 커버리지 안 — ADR-060 게이트를 통과한다.
    private val realClient = mockk<com.monticker.api.brokerage.infrastructure.BrokerageClient> { every { movesRealMoney } returns true }
    private val mockClient = mockk<com.monticker.api.brokerage.infrastructure.BrokerageClient> { every { movesRealMoney } returns false }
    private val priceFeedMonitor = mockk<PriceFeedMonitor> { every { isCovered(any()) } returns true }
    private val service = ConditionalOrderService(accountRepo, conditionalOrderRepo, jdbc,
        com.monticker.api.brokerage.infrastructure.BrokerageClientRegistry(BrokerageProvider.entries.associateWith { realClient }), priceFeedMonitor)

    private fun makeAccount() = BrokerageAccount(id = 1L, userId = 1L, provider = BrokerageProvider.MOCK, accountNumber = "12345678")

    private fun stubAccountAndStock() {
        every { accountRepo.findByUserIdAndIsActiveTrue(1L) } returns Optional.of(makeAccount())
        every { jdbc.queryForObject("SELECT id FROM stocks WHERE symbol = ?", Long::class.java, "005930") } returns 1L
        every { jdbc.queryForList("SELECT market FROM stocks WHERE id = ?", String::class.java, 1L) } returns listOf("KOSPI")
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
    fun `등록 시 90일 뒤로 만료일이 채워진다`() {
        // V-L2 — expiresAt이 계속 null이면 조건부 주문이 영원히 ACTIVE로 남는다.
        stubAccountAndStock()
        val savedSlot = slot<ConditionalOrder>()
        every { conditionalOrderRepo.save(capture(savedSlot)) } answers { savedSlot.captured }

        service.create(1L, "005930", OrderSide.SELL, 10, ConditionalOrderLeg(ConditionalTriggerType.STOP_LOSS, BigDecimal("70000"), OrderType.MARKET))

        val expiresAt = savedSlot.captured.expiresAt
        assertThat(expiresAt).isNotNull()
        // 기본 90일 — KST 날짜 경계(오늘+90일의 다음 날 00:00 KST)라 now+90일보다 늦고 하루 안쪽이다
        assertThat(expiresAt).isAfter(Instant.now().plus(90, ChronoUnit.DAYS))
        assertThat(expiresAt).isBefore(Instant.now().plus(91, ChronoUnit.DAYS).plusSeconds(60))
        assertThat(expiresAt!!.atZone(KST).toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT)
    }

    @Test
    fun `유효 기간 N일은 KST 날짜로 오늘+N일까지 — 그다음 날 00시(KST)에 만료된다`() {
        // 2026-10-08 23:30 KST = 14:30Z. UTC로 날짜를 셌다면 같은 날이지만, 00:30 KST(전날 15:30Z)에는 하루가 어긋난다.
        val lateNightKst = Instant.parse("2026-10-08T14:30:00Z")
        assertThat(service.expiryFor(1, lateNightKst)).isEqualTo(Instant.parse("2026-10-09T15:00:00Z"))   // 10-10 00:00 KST
        val justAfterMidnightKst = Instant.parse("2026-10-08T15:30:00Z")   // 10-09 00:30 KST
        assertThat(service.expiryFor(1, justAfterMidnightKst)).isEqualTo(Instant.parse("2026-10-10T15:00:00Z"))   // 10-11 00:00 KST
        assertThat(service.expiryFor(30, lateNightKst)).isEqualTo(Instant.parse("2026-11-07T15:00:00Z"))
        assertThat(service.expiryFor(null, lateNightKst)).isEqualTo(service.expiryFor(90, lateNightKst))
    }

    @Test
    fun `유효 기간이 범위(1~90일) 밖이면 계좌·종목을 보기 전에 거부한다`() {
        for (days in listOf(0, -1, 91, 365, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertThatThrownBy { service.create(1L, "005930", OrderSide.SELL, 10, stopLoss, validDays = days) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("유효 기간")
            assertThatThrownBy {
                service.createOco(1L, "005930", OrderSide.SELL, 10,
                    listOf(stopLoss, stopLoss.copy(triggerType = ConditionalTriggerType.TAKE_PROFIT, triggerPrice = BigDecimal("80000"))), validDays = days)
            }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("유효 기간")
        }
        verify(exactly = 0) { accountRepo.findByUserIdAndIsActiveTrue(any()) }
        verify(exactly = 0) { conditionalOrderRepo.save(any()) }
    }

    @Test
    fun `요청한 유효 기간으로 단일·OCO 모두 같은 만료 시각을 저장한다`() {
        stubAccountAndStock()
        val savedSlot = slot<ConditionalOrder>()
        every { conditionalOrderRepo.save(capture(savedSlot)) } answers { savedSlot.captured }
        every { conditionalOrderRepo.saveAll(any<List<ConditionalOrder>>()) } answers { firstArg() }

        service.create(1L, "005930", OrderSide.SELL, 10, stopLoss, validDays = 7)
        val single = savedSlot.captured.expiresAt!!
        val oco = service.createOco(1L, "005930", OrderSide.SELL, 10,
            listOf(stopLoss, stopLoss.copy(triggerType = ConditionalTriggerType.TAKE_PROFIT, triggerPrice = BigDecimal("80000"))), validDays = 7)

        val expected = java.time.LocalDate.now(KST).plusDays(8).atStartOfDay(KST).toInstant()
        assertThat(single).isEqualTo(expected)
        assertThat(oco.map { it.expiresAt }).containsOnly(expected)
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
        assertThat(result[0].expiresAt).isNotNull()
        assertThat(result[1].expiresAt).isEqualTo(result[0].expiresAt)
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

    @Test
    fun `남의 조건부 주문 취소는 없는 주문과 똑같이 응답한다`() {
        val order = ConditionalOrder(
            id = 1L, userId = 2L, accountId = 9L, stockId = 1L, symbol = "005930",
            side = OrderSide.SELL, triggerType = ConditionalTriggerType.STOP_LOSS, triggerPrice = BigDecimal("70000"),
            orderType = OrderType.MARKET, quantity = 10, status = ConditionalOrderStatus.ACTIVE,
        )
        every { conditionalOrderRepo.findById(1L) } returns Optional.of(order)
        val othersError = runCatching { service.cancel(1L, 1L) }.exceptionOrNull()

        every { conditionalOrderRepo.findById(1L) } returns Optional.empty()
        val missingError = runCatching { service.cancel(1L, 1L) }.exceptionOrNull()

        // 같은 예외·같은 메시지(→ 같은 404)여야 id 존재 여부를 열거할 수 없다
        assertThat(othersError).isInstanceOf(NoSuchElementException::class.java)
        assertThat(othersError!!.message).isEqualTo(missingError!!.message)
        assertThat(order.status).isEqualTo(ConditionalOrderStatus.ACTIVE)
        verify(exactly = 0) { conditionalOrderRepo.save(any()) }
    }

    // ── ADR-060 — 실시세 커버리지 ──────────────────────────────────────────────────────

    private val stopLoss = ConditionalOrderLeg(ConditionalTriggerType.STOP_LOSS, BigDecimal("70000"), OrderType.MARKET)

    @Test
    fun `실계좌는 실시세가 연결되지 않은 종목에 조건부 주문을 걸 수 없다 — 영영 발동하지 않으니까`() {
        stubAccountAndStock()
        every { priceFeedMonitor.isCovered(1L) } returns false

        assertThatThrownBy { service.create(1L, "005930", OrderSide.SELL, 10, stopLoss) }
            .isInstanceOf(com.monticker.api.common.exception.BusinessRuleException::class.java)
            .hasMessageContaining("실시간 시세")
        assertThatThrownBy { service.createOco(1L, "005930", OrderSide.SELL, 10, listOf(stopLoss, stopLoss.copy(triggerType = ConditionalTriggerType.TAKE_PROFIT, triggerPrice = BigDecimal("80000")))) }
            .isInstanceOf(com.monticker.api.common.exception.BusinessRuleException::class.java)
        verify(exactly = 0) { conditionalOrderRepo.save(any()) }
    }

    @Test
    fun `실제 돈을 움직이지 않는 증권사(Mock) 계좌는 커버리지와 무관하게 걸 수 있다`() {
        val mockService = ConditionalOrderService(accountRepo, conditionalOrderRepo, jdbc,
            com.monticker.api.brokerage.infrastructure.BrokerageClientRegistry(BrokerageProvider.entries.associateWith { mockClient }), priceFeedMonitor)
        stubAccountAndStock()
        every { priceFeedMonitor.isCovered(any()) } returns false
        every { conditionalOrderRepo.save(any()) } answers { firstArg() }

        mockService.create(1L, "005930", OrderSide.SELL, 10, stopLoss)

        verify(exactly = 1) { conditionalOrderRepo.save(any()) }
        verify(exactly = 0) { priceFeedMonitor.isCovered(any()) }
    }

    @Test
    fun `ADR-081 KRX 지정가가 호가 단위에 맞지 않으면 등록할 때 거부한다`() {
        stubAccountAndStock()

        assertThatThrownBy {
            service.create(1L, "005930", OrderSide.SELL, 10, ConditionalOrderLeg(ConditionalTriggerType.STOP_LOSS, BigDecimal("70000"), OrderType.LIMIT, BigDecimal("69950")))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("호가 단위")
    }
}
