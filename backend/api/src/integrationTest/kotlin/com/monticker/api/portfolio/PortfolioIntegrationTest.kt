package com.monticker.api.portfolio

import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.paper.application.HoldingResponse
import com.monticker.api.paper.application.PaperPortfolioQueryService
import com.monticker.api.portfolio.application.HoldingsScope
import com.monticker.api.portfolio.application.ManualTransactionInput
import com.monticker.api.portfolio.application.PortfolioAccountService
import com.monticker.api.portfolio.application.PortfolioHoldingsQueryService
import com.monticker.api.portfolio.domain.AccountKind
import com.monticker.api.portfolio.domain.Institution
import com.monticker.api.portfolio.domain.TransactionType
import com.monticker.api.portfolio.infrastructure.PortfolioAccountRepository
import com.monticker.api.portfolio.infrastructure.PortfolioTransactionRepository
import com.monticker.api.support.PostgresIntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 통합 포트폴리오(ADR-106 1단계)를 실제 Postgres(V104)로 본다 — 손 입력 계좌·거래, 여러 계좌의 같은 종목 합치기,
 * 통화별 합계, 시세 없는 상품, 남의 계좌 격리, 계좌 삭제 시 거래 삭제, 입력 검증, 모의계좌 범위.
 */
class PortfolioIntegrationTest : PostgresIntegrationTest() {

    private val paper = mockk<PaperPortfolioQueryService>()
    private val accounts by lazy { PortfolioAccountRepository(jdbcTemplate) }
    private val txs by lazy { PortfolioTransactionRepository(jdbcTemplate) }
    private val service by lazy {
        PortfolioAccountService(accounts, txs, jdbcTemplate).apply {
            clock = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneId.of("Asia/Seoul"))
        }
    }
    private val holdings by lazy { PortfolioHoldingsQueryService(accounts, txs, paper, jdbcTemplate) }

    private var me = 0L
    private var other = 0L
    private var samsung = 0L
    private var avgo = 0L

    private fun user() = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id", Long::class.java, "p-${UUID.randomUUID()}@test.local", "p",
    )!!

    private fun stock(symbol: String, market: String, currency: String) = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange, currency) VALUES (?, ?, ?, 'X', ?) RETURNING id",
        Long::class.java, symbol + UUID.randomUUID().toString().take(6), "$symbol 이름", market, currency,
    )!!

    private fun price(stockId: Long, close: String) = jdbcTemplate.update(
        "INSERT INTO candles_1m (stock_id, open, high, low, close, volume, candle_time) VALUES (?, ?, ?, ?, ?, 1, now())",
        stockId, BigDecimal(close), BigDecimal(close), BigDecimal(close), BigDecimal(close),
    )

    @BeforeEach
    fun setUp() {
        me = user(); other = user()
        samsung = stock("005930", "KOSPI", "KRW"); price(samsung, "80000")
        avgo = stock("AVGO", "NASDAQ", "USD"); price(avgo, "300")
    }

    private fun buy(account: Long, stockId: Long?, qty: String, px: String, date: String = "2026-10-01", fee: String = "0", code: String? = null) =
        service.addManual(me, account, ManualTransactionInput(
            type = TransactionType.BUY, tradeDate = LocalDate.parse(date), stockId = stockId, instrumentCode = code,
            instrumentName = code?.let { "채권형 펀드" }, quantity = BigDecimal(qty), price = BigDecimal(px), fee = BigDecimal(fee),
        ))

    @Test
    fun `두 계좌의 같은 종목을 합쳐 평균 원가·평가금액·비중을 낸다`() {
        val kis = service.createManual(me, "한투 일반", Institution.KIS, AccountKind.GENERAL, "1234").id
        val isa = service.createManual(me, "키움 ISA", Institution.KIWOOM, AccountKind.ISA, null).id
        buy(kis, samsung, "10", "70000", fee = "100")
        buy(isa, samsung, "10", "74000")
        service.addManual(me, isa, ManualTransactionInput(TransactionType.SELL, LocalDate.parse("2026-10-10"), stockId = samsung,
            quantity = BigDecimal("4"), price = BigDecimal("78000")))

        val view = holdings.holdings(me, HoldingsScope.REAL)
        val h = view.holdings.single()
        assertThat(h.quantity).isEqualByComparingTo("16")
        assertThat(h.costBasis).isEqualByComparingTo("1144100")        // 700,100 + (740,000 - 4 × 74,000)
        assertThat(h.avgCost).isEqualByComparingTo("71506.25")
        assertThat(h.currentPrice).isEqualByComparingTo("80000")
        assertThat(h.marketValue).isEqualByComparingTo("1280000")
        assertThat(h.unrealizedPnl).isEqualByComparingTo("135900")
        assertThat(h.realizedPnl).isEqualByComparingTo("16000")         // 4 × (78,000 - 74,000)
        assertThat(h.weight).isEqualTo(100.0)
        assertThat(h.accounts.map { it.accountName to it.quantity.toPlainString() })
            .containsExactlyInAnyOrder("한투 일반" to "10", "키움 ISA" to "6")
        assertThat(view.totals.single().currency).isEqualTo("KRW")
        assertThat(service.list(me).first { it.id == kis }.accountRefMasked).isEqualTo("****1234")
    }

    @Test
    fun `통화별로 합계를 따로 내고 시세 없는 상품은 평가금액에서 빼고 센다`() {
        val acc = service.createManual(me, "통합", Institution.OTHER, AccountKind.GENERAL, null).id
        buy(acc, samsung, "1", "70000")
        buy(acc, avgo, "0.5", "280")
        buy(acc, null, "1000", "1.02", code = "KR5223941K16")

        val view = holdings.holdings(me, HoldingsScope.REAL)
        val krw = view.totals.first { it.currency == "KRW" }
        val usd = view.totals.first { it.currency == "USD" }
        assertThat(view.totals.map { it.currency }).containsExactly("KRW", "USD")
        assertThat(krw.costBasis).isEqualByComparingTo("71020")          // 70,000 + 1,020
        assertThat(krw.marketValue).isEqualByComparingTo("80000")        // 펀드는 시세 없음
        assertThat(krw.unpricedCount).isEqualTo(1)
        assertThat(usd.marketValue).isEqualByComparingTo("150")
        val fund = view.holdings.first { it.instrumentCode == "KR5223941K16" }
        assertThat(fund.currentPrice).isNull()
        assertThat(fund.name).isEqualTo("채권형 펀드")
        assertThat(fund.weight).isNull()
    }

    @Test
    fun `남의 계좌는 보이지도 지워지지도 않는다`() {
        val acc = service.createManual(me, "내 계좌", Institution.TOSS, AccountKind.GENERAL, null).id
        val txId = buy(acc, samsung, "1", "70000")

        assertThat(service.list(other)).isEmpty()
        assertThat(holdings.holdings(other, HoldingsScope.REAL).holdings).isEmpty()
        assertThatThrownBy { service.transactions(other, acc, 0, 10) }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { service.deleteTransaction(other, acc, txId) }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { service.delete(other, acc) }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { service.addManual(other, acc, ManualTransactionInput(TransactionType.DEPOSIT, LocalDate.parse("2026-10-01"), amount = BigDecimal.TEN)) }
            .isInstanceOf(NoSuchElementException::class.java)
        assertThat(holdings.holdings(me, HoldingsScope.REAL).holdings).hasSize(1)
    }

    @Test
    fun `계좌를 지우면 거래도 남지 않는다`() {
        val acc = service.createManual(me, "지울 계좌", Institution.NH, AccountKind.GENERAL, null).id
        buy(acc, samsung, "1", "70000")
        service.delete(me, acc)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM portfolio_transactions WHERE account_id = ?", Int::class.java, acc)).isZero()
        assertThat(holdings.holdings(me, HoldingsScope.REAL).holdings).isEmpty()
    }

    @Test
    fun `잘못된 입력은 400(IllegalArgumentException)으로 막는다`() {
        val acc = service.createManual(me, "검증", Institution.KIS, AccountKind.GENERAL, null).id
        fun add(input: ManualTransactionInput) = service.addManual(me, acc, input)
        val d = LocalDate.parse("2026-10-01")
        assertThatThrownBy { add(ManualTransactionInput(TransactionType.BUY, LocalDate.parse("2026-10-20"), samsung, quantity = BigDecimal.ONE, price = BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("미래")
        assertThatThrownBy { add(ManualTransactionInput(TransactionType.BUY, d, avgo, quantity = BigDecimal.ONE, price = BigDecimal.ONE, currency = "KRW")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("USD")
        assertThatThrownBy { add(ManualTransactionInput(TransactionType.BUY, d, quantity = BigDecimal.ONE, price = BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("종목")
        assertThatThrownBy { add(ManualTransactionInput(TransactionType.SELL, d, samsung, quantity = BigDecimal.ZERO, price = BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { add(ManualTransactionInput(TransactionType.DIVIDEND, d, amount = BigDecimal.TEN)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("배당")
        assertThatThrownBy { add(ManualTransactionInput(TransactionType.BUY, d, 999_999_999, quantity = BigDecimal.ONE, price = BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("종목을 찾을 수 없습니다")
        assertThatThrownBy { service.createManual(me, "x", Institution.KIS, AccountKind.GENERAL, "12345") }
            .isInstanceOf(IllegalArgumentException::class.java)
        // 같은 날 같은 거래를 두 번 넣는 것은 허용한다(실제로 있다)
        repeat(2) { add(ManualTransactionInput(TransactionType.BUY, d, samsung, quantity = BigDecimal.ONE, price = BigDecimal("70000"))) }
        assertThat(holdings.holdings(me, HoldingsScope.REAL).holdings.single().quantity).isEqualByComparingTo("2")
    }

    @Test
    fun `계좌는 20개까지다`() {
        repeat(20) { service.createManual(me, "계좌$it", Institution.OTHER, AccountKind.GENERAL, null) }
        assertThatThrownBy { service.createManual(me, "21번째", Institution.OTHER, AccountKind.GENERAL, null) }
            .isInstanceOf(BusinessRuleException::class.java)
    }

    @Test
    fun `모의계좌는 PAPER 범위로 따로 보이고 REAL 합계에 섞이지 않는다`() {
        every { paper.buildHoldings(me) } returns listOf(
            HoldingResponse(samsung, "005930", "삼성전자", 3, BigDecimal("70000"), BigDecimal("80000"), BigDecimal("240000"), BigDecimal("30000"), 14.28),
        )
        val view = holdings.holdings(me, HoldingsScope.PAPER)
        val h = view.holdings.single()
        assertThat(h.quantity).isEqualByComparingTo("3")
        assertThat(h.costBasis).isEqualByComparingTo("210000")
        assertThat(h.marketValue).isEqualByComparingTo("240000")
        assertThat(h.accounts.single().accountName).isEqualTo("모의계좌")
        assertThat(holdings.holdings(me, HoldingsScope.REAL).holdings).isEmpty()
    }
}
