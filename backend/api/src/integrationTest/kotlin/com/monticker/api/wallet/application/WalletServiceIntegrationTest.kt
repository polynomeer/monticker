package com.monticker.api.wallet.application

import com.monticker.api.common.domain.Money
import com.monticker.api.paper.application.PaperAccountQueryService
import com.monticker.api.support.PostgresIntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * calcHoldingsValue는 portfolio_positions와 candles_1m을 JOIN LATERAL로 묶는 단일 SQL로
 * 보유 평가액을 계산한다 — "가격을 못 구한 종목은 스킵한다"는 동작이 애플리케이션 코드가
 * 아니라 이 SQL의 조인 의미에 내재되어 있다. JdbcTemplate을 mock하는 단위 테스트
 * (WalletServiceTest)는 mock이 시키는 대로 아무 BigDecimal이나 돌려주므로 실제 조인
 * 시맨틱을 검증하지 못한다 — 그래서 실제 Postgres로 검증한다.
 */
class WalletServiceIntegrationTest : PostgresIntegrationTest() {

    private val accountQueryService = mockk<PaperAccountQueryService>()
    private val ledgerService = mockk<LedgerService>()

    private fun createUser(email: String): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
            Long::class.java,
            email, "지갑-$email",
        )!!

    private fun createStock(symbol: String): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, ?, 'KOSPI', 'KRX') RETURNING id",
            Long::class.java,
            symbol, "종목-$symbol",
        )!!

    private fun insertPosition(userId: Long, stockId: Long, netQty: Int) {
        jdbcTemplate.update(
            "INSERT INTO portfolio_positions (user_id, stock_id, net_qty) VALUES (?, ?, ?)",
            userId, stockId, netQty,
        )
    }

    private fun insertCandle(stockId: Long, close: BigDecimal) {
        jdbcTemplate.update(
            """
            INSERT INTO candles_1m (stock_id, open, high, low, close, candle_time)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            stockId, close, close, close, close, java.sql.Timestamp.from(Instant.now()),
        )
    }

    @Test
    fun `getWalletMap excludes a holding with no matching candle from holdingsValue while including a resolvable one`() {
        val userId = createUser("wallet-int-${System.nanoTime()}@monticker.test")
        val stockWithPrice = createStock("WAL${System.nanoTime() % 100000}")
        val stockWithoutPrice = createStock("WAL${(System.nanoTime() + 1) % 100000}")

        insertPosition(userId, stockWithPrice, netQty = 10)
        insertPosition(userId, stockWithoutPrice, netQty = 5)
        insertCandle(stockWithPrice, close = BigDecimal("60000"))
        // stockWithoutPrice deliberately has no candles_1m row.

        every { accountQueryService.getCashBalance(userId) } returns Money.of("1000000")
        every { ledgerService.getLedger(userId) } returns emptyList()

        val service = WalletService(accountQueryService, ledgerService, jdbcTemplate)
        val result = service.getWalletMap(userId)

        // holdingsValue = 10 * 60000 (stockWithoutPrice contributes nothing, not even a zero row)
        assertThat(result.holdingsValue).isEqualByComparingTo(BigDecimal("600000"))
        assertThat(result.totalAssets).isEqualByComparingTo(BigDecimal("1600000"))
    }
}
