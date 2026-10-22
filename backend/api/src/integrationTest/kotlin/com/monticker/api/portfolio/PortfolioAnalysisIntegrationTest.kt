package com.monticker.api.portfolio

import com.monticker.api.common.consent.ConsentService
import com.monticker.api.paper.application.PaperPortfolioQueryService
import com.monticker.api.portfolio.application.AllocationDimension
import com.monticker.api.portfolio.application.PortfolioAccountService
import com.monticker.api.portfolio.application.PortfolioAnalysisService
import com.monticker.api.portfolio.application.PortfolioHoldingsQueryService
import com.monticker.api.portfolio.application.TransactionInput
import com.monticker.api.portfolio.application.TransactionNormalizer
import com.monticker.api.portfolio.domain.AccountKind
import com.monticker.api.portfolio.domain.AccountSource
import com.monticker.api.portfolio.domain.Institution
import com.monticker.api.portfolio.domain.TransactionType
import com.monticker.api.portfolio.infrastructure.KrwRates
import com.monticker.api.portfolio.infrastructure.PortfolioAccountRepository
import com.monticker.api.portfolio.infrastructure.PortfolioTransactionRepository
import com.monticker.api.support.PostgresIntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 통합 포트폴리오 분석(ADR-111)을 손으로 계산한 사례와 대조한다 — 실제 Postgres, 원/달러는 market_index_* 행으로.
 *
 * | 종목 | 계좌 | 매수 | 지금 | 원화 |
 * |---|---|---|---|---|
 * | 국내 A(IT, KR) | 일반·한투 | 10주 × 70,000 + 수수료 100 = 700,100원 (2025-01-02) | 80,000원 → 800,000원 | 평가손익 99,900 |
 * | 미국 B(Tech, US) | ISA·키움 | 2주 × $100 = $200, 그날 1,300원 → 260,000원 (2025-03-03) | $150 → $300, 지금 1,400원 → 420,000원 | 주가 140,000 + 환차 20,000 |
 * | 일본 펀드(코드만, JPY) | 일반·한투 | 시세 없음 | — | 합계에서 빠짐(환산 불가 통화) |
 *
 * 합계: 평가 1,220,000 · 원가 960,100 · 평가손익 259,900 = 주가 239,900 + 환차 20,000.
 * 배당(최근 12개월, 오늘 2026-10-19): A 2026-04-15 1,000 − 세금 154 = 846원, B 2026-05-10 $1 − $0.15 = $0.85 × 1,350(05-08 종가) = 1,147.5원.
 */
class PortfolioAnalysisIntegrationTest : PostgresIntegrationTest() {

    private val consents = mockk<ConsentService> {
        every { requireMissingAndRecord(any(), any(), any(), any()) } returns emptySet()
        every { missingRequired(any(), any()) } returns emptySet()
    }
    private val accounts by lazy { PortfolioAccountRepository(jdbcTemplate) }
    private val txs by lazy { PortfolioTransactionRepository(jdbcTemplate) }
    private val fixed = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val service by lazy {
        PortfolioAccountService(accounts, txs, TransactionNormalizer(jdbcTemplate).apply { clock = fixed }, consents)
    }
    private val holdings by lazy { PortfolioHoldingsQueryService(accounts, txs, mockk<PaperPortfolioQueryService>(), jdbcTemplate) }
    private val analysis by lazy { PortfolioAnalysisService(accounts, txs, holdings, KrwRates(jdbcTemplate), jdbcTemplate).apply { clock = fixed } }

    private var me = 0L
    private var a = 0L
    private var b = 0L

    private fun stock(name: String, sector: String, country: String, currency: String) = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange, sector, country, currency) VALUES (?, ?, 'X', 'X', ?, ?, ?) RETURNING id",
        Long::class.java, "S" + UUID.randomUUID().toString().take(8), name, sector, country, currency,
    )!!

    private fun price(stockId: Long, close: String) = jdbcTemplate.update(
        "INSERT INTO candles_1m (stock_id, open, high, low, close, volume, candle_time) VALUES (?, ?, ?, ?, ?, 1, now())",
        stockId, BigDecimal(close), BigDecimal(close), BigDecimal(close), BigDecimal(close),
    )

    private fun usdKrw(now: String, mocked: Boolean, vararg daily: Pair<String, String>) {
        jdbcTemplate.update("DELETE FROM market_index_quotes WHERE code = 'USDKRW'")
        jdbcTemplate.update("DELETE FROM market_index_daily WHERE code = 'USDKRW'")
        jdbcTemplate.update(
            "INSERT INTO market_index_quotes (code, name, value, as_of, source, is_mocked) VALUES ('USDKRW', '원/달러', ?, now(), 'TEST', ?)",
            BigDecimal(now), mocked,
        )
        daily.forEach { (d, c) ->
            jdbcTemplate.update("INSERT INTO market_index_daily (code, trade_date, close, is_mocked) VALUES ('USDKRW', ?, ?, ?)", LocalDate.parse(d), BigDecimal(c), mocked)
        }
    }

    private fun add(account: Long, type: TransactionType, date: String, stockId: Long? = null, qty: String? = null, px: String? = null,
                    amount: String? = null, fee: String = "0", tax: String = "0", code: String? = null, currency: String? = null) =
        service.addManual(me, account, TransactionInput(
            type = type, tradeDate = LocalDate.parse(date), stockId = stockId, instrumentCode = code, instrumentName = code?.let { "일본 펀드" },
            quantity = qty?.let(::BigDecimal), price = px?.let(::BigDecimal), amount = amount?.let(::BigDecimal),
            fee = BigDecimal(fee), tax = BigDecimal(tax), currency = currency,
        ))

    @BeforeEach
    fun setUp() {
        me = jdbcTemplate.queryForObject("INSERT INTO users (email, nickname) VALUES (?, 'an') RETURNING id", Long::class.java, "an-${UUID.randomUUID()}@test.local")!!
        a = stock("국내A", "IT", "KR", "KRW").also { price(it, "80000") }
        b = stock("미국B", "Tech", "US", "USD").also { price(it, "150") }
        usdKrw("1400", mocked = false, "2025-03-03" to "1300", "2026-05-08" to "1350")
        val kis = service.create(me, AccountSource.MANUAL, "한투 일반", Institution.KIS, AccountKind.GENERAL, null).id
        val isa = service.create(me, AccountSource.MANUAL, "키움 ISA", Institution.KIWOOM, AccountKind.ISA, null).id
        add(kis, TransactionType.BUY, "2025-01-02", a, "10", "70000", fee = "100")
        add(isa, TransactionType.BUY, "2025-03-03", b, "2", "100")
        add(kis, TransactionType.BUY, "2025-06-02", code = "JP-FUND-1", qty = "3", px = "1000", currency = "JPY")
        add(kis, TransactionType.DIVIDEND, "2026-04-15", a, amount = "1000", tax = "154")
        add(isa, TransactionType.DIVIDEND, "2026-05-10", b, amount = "1", tax = "0.15")
        add(kis, TransactionType.DEPOSIT, "2025-01-01", amount = "5000000")     // 입금은 수익률 흐름에 넣지 않는다
    }

    @Test
    fun `원화 요약은 주가 손익과 환차손익을 나누고, 환산할 수 없는 통화·시세 없는 종목은 빼고 밝힌다`() {
        val k = analysis.analysis(me).krw
        assertThat(k.marketValueKrw).isEqualByComparingTo("1220000")
        assertThat(k.costBasisKrw).isEqualByComparingTo("960100")
        assertThat(k.unrealizedPnlKrw).isEqualByComparingTo("259900")
        assertThat(k.pricePnlKrw).isEqualByComparingTo("239900")
        assertThat(k.fxPnlKrw).isEqualByComparingTo("20000")
        assertThat(k.excludedCurrencies).containsExactly("JPY")
        assertThat(k.unpricedCount).isEqualTo(1)
        assertThat(k.fxMocked).isFalse()
    }

    @Test
    fun `배분은 원화 평가금액 기준이고 계좌 종류·증권사로도 나눈다`() {
        val byDim = analysis.analysis(me).allocations.associateBy { it.dimension }
        val sector = byDim.getValue(AllocationDimension.SECTOR).slices
        assertThat(sector.map { it.key }).containsExactly("IT", "Tech")
        assertThat(sector[0].valueKrw).isEqualByComparingTo("800000")
        assertThat(sector[0].weight).isCloseTo(65.5738, within(1e-3))
        assertThat(byDim.getValue(AllocationDimension.COUNTRY).slices.map { it.key }).containsExactly("KR", "US")
        assertThat(byDim.getValue(AllocationDimension.ACCOUNT_KIND).slices.map { it.key to it.valueKrw.toInt() }).containsExactly("GENERAL" to 800000, "ISA" to 420000)
        assertThat(byDim.getValue(AllocationDimension.INSTITUTION).slices.map { it.key }).containsExactly("KIS", "KIWOOM")
    }

    @Test
    fun `배당은 최근 12개월을 달마다, 그날 환율로 원화로 바꾸고 원가 대비 배당률을 낸다`() {
        val d = analysis.analysis(me).dividends
        assertThat(d.months).hasSize(12)
        assertThat(d.months.first().month).isEqualTo("2025-11")
        assertThat(d.months.last().month).isEqualTo("2026-10")
        assertThat(d.months.single { it.month == "2026-04" }.krw).isEqualByComparingTo("846")
        val may = d.months.single { it.month == "2026-05" }
        assertThat(may.byCurrency.getValue("USD")).isEqualByComparingTo("0.85")
        assertThat(may.krw).isEqualByComparingTo("1147.5")
        assertThat(d.trailing12mKrw).isEqualByComparingTo("1993.5")
        assertThat(d.yieldOnCost).isCloseTo(0.207634, within(1e-5))
        assertThat(d.topStocks.map { it.name }).containsExactly("미국B", "국내A")
    }

    @Test
    fun `수익률은 통화마다 돈 가중(XIRR)이고 입금은 흐름에 넣지 않는다 — 시세 없는 통화는 이유를 준다`() {
        val r = analysis.analysis(me).returns.associateBy { it.currency }
        val krw = r.getValue("KRW")
        assertThat(krw.xirr).isCloseTo(7.78227, within(1e-3))
        assertThat(krw.invested).isEqualByComparingTo("700100")
        assertThat(krw.returned).isEqualByComparingTo("846")
        assertThat(krw.since).isEqualTo(LocalDate.parse("2025-01-02"))
        assertThat(r.getValue("USD").xirr).isCloseTo(28.4886, within(1e-3))
        assertThat(r.getValue("JPY").xirr).isNull()
        assertThat(r.getValue("JPY").reason).contains("시세")
        assertThat(r.keys).containsExactly("KRW", "JPY", "USD")
    }

    @Test
    fun `매수일 환율 이력이 없으면 원가·환차손익은 비우고, 모의 환율이면 표시한다`() {
        usdKrw("1400", mocked = true, "2026-05-08" to "1350")     // 2025-03-03 이전 이력 없음
        val k = analysis.analysis(me).krw
        assertThat(k.marketValueKrw).isEqualByComparingTo("1220000")
        assertThat(k.pricePnlKrw).isEqualByComparingTo("239900")
        assertThat(k.costBasisKrw).isNull()
        assertThat(k.fxPnlKrw).isNull()
        assertThat(k.fxMocked).isTrue()
    }
}
