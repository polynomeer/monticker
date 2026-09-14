package com.monticker.api.paper.application

import com.monticker.api.paper.domain.PaperAccount
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
    private val portfolioQueryService = mockk<PaperPortfolioQueryService>(relaxed = true)
    private val projection = mockk<PortfolioPositionProjection>(relaxed = true)
    private val settlementService = mockk<PaperSettlementService>(relaxed = true)

    private val service = PaperTradingService(
        accountRepo, tradeRepo, jdbc, eventPublisher, portfolioQueryService, projection, settlementService,
    )

    @Test
    fun `buy throws a business IllegalStateException (not a raw DB exception) when the stock has no recent candle`() {
        every { accountRepo.findByUserId(1L) } returns Optional.of(PaperAccount(userId = 1L))
        every {
            jdbc.query(any<String>(), any<org.springframework.jdbc.core.RowMapper<java.math.BigDecimal>>(), 999L)
        } returns emptyList()

        assertThatThrownBy { service.buy(userId = 1L, stockId = 999L, quantity = 1) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("현재가")
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
