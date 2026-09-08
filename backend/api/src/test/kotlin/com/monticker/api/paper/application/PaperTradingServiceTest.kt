package com.monticker.api.paper.application

import com.monticker.api.paper.domain.PaperAccount
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
}
