package com.monticker.api.paper.application

import com.monticker.api.matching.submit.MarketOrderResult
import com.monticker.api.matching.submit.OrderSubmitter
import com.monticker.api.paper.domain.PaperAccount
import com.monticker.api.paper.domain.PaperTrade
import com.monticker.api.paper.events.PaperAccountResetEvent
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import com.monticker.api.paper.infrastructure.PaperTradeRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Optional

/**
 * 부하 테스트 중 실제로 재현된 버그: `candles_1m`에 해당 종목 행이 0건이면
 * `jdbc.queryForObject(...)`가 null이 아니라 EmptyResultDataAccessException을 던져서
 * `?: throw IllegalStateException(...)` 처리가 무력화되고, GlobalExceptionHandler의
 * catch-all에 잡혀 안내 메시지 없는 500으로 샜다. query+firstOrNull로 바꿔 실제로
 * IllegalStateException이 나오는지(그래서 GlobalExceptionHandler가 409로 분류할 수 있는지)
 * 검증한다.
 */
class PaperTradingServiceTest {

    private val accountRepo = mockk<PaperAccountRepository>()
    private val tradeRepo = mockk<PaperTradeRepository>(relaxed = true)
    private val jdbc = mockk<JdbcTemplate>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val projection = mockk<PortfolioPositionProjection>(relaxed = true)
    private val orderSubmitter = mockk<OrderSubmitter>()

    private val service = PaperTradingService(
        accountRepo, tradeRepo, jdbc, eventPublisher, projection, orderSubmitter,
    )

    // ADR-047 — buy/sell은 파사드다: 매칭 엔진에 MARKET 주문을 내고, 리스너가 같은 트랜잭션에 만든 paper_trades 행을 돌려준다.
    @Test
    fun `buy submits a MARKET order to the matching engine and returns the mirrored trade id`() {
        val account = PaperAccount(userId = 1L, cash = com.monticker.api.common.domain.Money.of("9000000"))
        every { accountRepo.findByUserId(1L) } returns Optional.of(account)
        every { orderSubmitter.submitMarket(1L, 5L, "BUY", 3) } returns MarketOrderResult(
            orderId = 10L, fillId = 77L, stockId = 5L, side = "BUY", quantity = 3,
            fillPrice = java.math.BigDecimal("65000"), amount = java.math.BigDecimal("195000"), filledAt = java.time.Instant.now(),
        )
        every { tradeRepo.findByFillId(77L) } returns PaperTrade(id = 500L, userId = 1L, stockId = 5L, side = "BUY", quantity = 3,
            price = java.math.BigDecimal("65000"), amount = java.math.BigDecimal("195000"), fillId = 77L)
        // 잔고는 사가가 JDBC로 바꾼 값을 JDBC로 읽는다 — JPA 캐시의 엔티티(9,000,000)가 아니라
        every { jdbc.query(match<String> { it.contains("SELECT cash") }, any<org.springframework.jdbc.core.RowMapper<java.math.BigDecimal>>(), 1L) } returns listOf(java.math.BigDecimal("8805000"))

        val result = service.buy(userId = 1L, stockId = 5L, quantity = 3)

        org.assertj.core.api.Assertions.assertThat(result.tradeId).isEqualTo(500L)
        org.assertj.core.api.Assertions.assertThat(result.amount).isEqualByComparingTo("195000")
        org.assertj.core.api.Assertions.assertThat(result.remainingCash).isEqualByComparingTo("8805000")
        // 직접 잔고를 바꾸거나 거래를 저장하지 않는다 — 그건 사가와 리스너의 몫이다
        io.mockk.verify(exactly = 0) { accountRepo.save(any()) }
        io.mockk.verify(exactly = 0) { tradeRepo.save(any()) }
    }

    @Test
    fun `a risk rejection from the matching engine propagates unchanged`() {
        every { accountRepo.findByUserId(1L) } returns Optional.of(PaperAccount(userId = 1L))
        every { orderSubmitter.submitMarket(1L, 5L, "SELL", 1) } throws com.monticker.api.common.aop.RiskLimitException("DailyLossRule")

        assertThatThrownBy { service.sell(userId = 1L, stockId = 5L, quantity = 1) }
            .isInstanceOf(com.monticker.api.common.aop.RiskLimitException::class.java)
    }

    // ADR-043 — 초기화는 현금 컬럼을 바꾸는 경로다. 이벤트가 없으면 원장에 구멍이 나고 대사가 영구히 어긋난다.
    @Test
    fun `reset publishes the cash change so the wallet can record it in the ledger`() {
        val account = PaperAccount(userId = 7L, cash = com.monticker.api.common.domain.Money.of("4000000"))
        every { accountRepo.findByUserId(7L) } returns Optional.of(account)
        every { accountRepo.save(any()) } answers { firstArg() }
        every { jdbc.queryForObject(match<String> { it.contains("FROM orders") }, Long::class.java, 7L) } returns 0L
        every { jdbc.update(any<String>(), 7L) } returns 0
        val published = mutableListOf<Any>()
        every { eventPublisher.publishEvent(capture(published)) } returns Unit

        service.reset(7L)

        val event = published.filterIsInstance<PaperAccountResetEvent>().single()
        org.assertj.core.api.Assertions.assertThat(event.previousCash).isEqualByComparingTo("4000000")
        org.assertj.core.api.Assertions.assertThat(event.newCash).isEqualByComparingTo("10000000")
    }

    // 예약금이 cash에서 빠진 채 초기화하면 나중의 취소 환불이 1,000만 위에 얹힌다 — 돈이 생긴다.
    @Test
    fun `reset refuses while the user has open orders, touching neither the account nor the trades`() {
        every { jdbc.queryForObject(match<String> { it.contains("FROM orders") }, Long::class.java, 7L) } returns 2L

        assertThatThrownBy { service.reset(7L) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("미체결 주문 2건")

        io.mockk.verify(exactly = 0) { accountRepo.save(any()) }
        io.mockk.verify(exactly = 0) { jdbc.update(any<String>(), 7L) }
    }
}
