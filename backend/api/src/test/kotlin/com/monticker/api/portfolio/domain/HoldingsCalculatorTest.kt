package com.monticker.api.portfolio.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 거래 원장 → 포지션 계산을 손으로 계산한 값과 대조한다(ADR-106). 이동평균 원가, 수수료·세금, 부분 매도,
 * 초과 매도(앞 구간 누락), 배당, 해외 소수점 주식, 같은 날 순서를 본다.
 */
class HoldingsCalculatorTest {

    private var seq = 0L
    private fun tx(
        type: TransactionType, date: String, stockId: Long? = 5930, qty: String? = null, price: String? = null,
        amount: String? = null, fee: String = "0", tax: String = "0", currency: String = "KRW", code: String? = null,
    ) = PortfolioTransaction(
        id = ++seq, accountId = 1, type = type, tradeDate = LocalDate.parse(date), stockId = stockId,
        instrumentCode = code, instrumentName = null,
        quantity = qty?.let(::BigDecimal), price = price?.let(::BigDecimal),
        amount = BigDecimal(amount ?: (BigDecimal(qty!!) * BigDecimal(price!!)).toPlainString()),
        fee = BigDecimal(fee), tax = BigDecimal(tax), currency = currency, memo = null,
    )

    private fun calc(vararg txs: PortfolioTransaction) = HoldingsCalculator.calculate(txs.toList())

    @Test
    fun `이동평균 원가에 매수 수수료를 넣는다`() {
        val p = calc(
            tx(TransactionType.BUY, "2026-01-02", qty = "10", price = "70000", fee = "100"),
            tx(TransactionType.BUY, "2026-02-02", qty = "10", price = "80000", fee = "100"),
        ).positions.single()
        assertThat(p.quantity).isEqualByComparingTo("20")
        assertThat(p.costBasis).isEqualByComparingTo("1500200")       // 700,000 + 100 + 800,000 + 100
        assertThat(p.avgCost).isEqualByComparingTo("75010")
        assertThat(p.realizedPnl).isEqualByComparingTo("0")
    }

    @Test
    fun `부분 매도는 그때의 평균 원가로 실현손익을 내고 매도 수수료·세금을 뺀다`() {
        val p = calc(
            tx(TransactionType.BUY, "2026-01-02", qty = "10", price = "70000"),
            tx(TransactionType.BUY, "2026-01-03", qty = "10", price = "80000"),
            // 평균 75,000. 5주를 90,000에 팔고 수수료 100, 거래세 810
            tx(TransactionType.SELL, "2026-02-01", qty = "5", price = "90000", fee = "100", tax = "810"),
        ).positions.single()
        assertThat(p.quantity).isEqualByComparingTo("15")
        assertThat(p.costBasis).isEqualByComparingTo("1125000")       // 1,500,000 - 5 × 75,000
        assertThat(p.avgCost).isEqualByComparingTo("75000")           // 매도해도 평균은 그대로
        assertThat(p.realizedPnl).isEqualByComparingTo("74090")       // 450,000 - 910 - 375,000
    }

    @Test
    fun `다 팔면 포지션이 닫히고 실현손익은 남는다`() {
        val p = calc(
            tx(TransactionType.BUY, "2026-01-02", qty = "3", price = "100"),
            tx(TransactionType.SELL, "2026-01-05", qty = "3", price = "90"),
        ).positions.single()
        assertThat(p.isOpen).isFalse()
        assertThat(p.avgCost).isNull()
        assertThat(p.costBasis).isEqualByComparingTo("0")
        assertThat(p.realizedPnl).isEqualByComparingTo("-30")
    }

    @Test
    fun `가진 것보다 많이 팔면 가진 만큼만 계산하고 확인 필요로 표시한다`() {
        val p = calc(
            tx(TransactionType.BUY, "2026-01-02", qty = "2", price = "100"),
            // 앞 구간 매수 기록이 빠져 4주를 판 것으로 보인다 — 2주분 매도 금액만 원가와 맞춘다
            tx(TransactionType.SELL, "2026-01-05", qty = "4", price = "150"),
        ).positions.single()
        assertThat(p.oversold).isTrue()
        assertThat(p.quantity).isEqualByComparingTo("0")
        assertThat(p.realizedPnl).isEqualByComparingTo("100")         // 2 × 150 - 2 × 100
    }

    @Test
    fun `같은 날은 입력 순서대로 처리한다 — 매도가 먼저 들어오면 초과 매도다`() {
        val sellFirst = calc(
            tx(TransactionType.SELL, "2026-01-02", qty = "1", price = "100"),
            tx(TransactionType.BUY, "2026-01-02", qty = "1", price = "100"),
        ).positions.single()
        assertThat(sellFirst.oversold).isTrue()
        assertThat(sellFirst.quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `날짜가 섞여 들어와도 날짜순으로 계산한다`() {
        val p = calc(
            tx(TransactionType.SELL, "2026-03-01", qty = "1", price = "200"),
            tx(TransactionType.BUY, "2026-01-01", qty = "2", price = "100"),
        ).positions.single()
        assertThat(p.oversold).isFalse()
        assertThat(p.quantity).isEqualByComparingTo("1")
        assertThat(p.realizedPnl).isEqualByComparingTo("100")
    }

    @Test
    fun `배당은 세금을 뺀 금액으로 종목에 붙고 현금에 들어온다`() {
        val result = calc(
            tx(TransactionType.DEPOSIT, "2026-01-01", stockId = null, amount = "1000000"),
            tx(TransactionType.BUY, "2026-01-02", qty = "10", price = "70000", fee = "100"),
            tx(TransactionType.DIVIDEND, "2026-04-20", amount = "3610", tax = "555"),
        )
        assertThat(result.positions.single().dividendsNet).isEqualByComparingTo("3055")
        assertThat(result.cashByCurrency["KRW"]).isEqualByComparingTo("302955")   // 1,000,000 - 700,100 + 3,055
    }

    @Test
    fun `해외 소수점 주식은 통화별로 따로 묶고 소수 수량을 그대로 쓴다`() {
        val result = calc(
            tx(TransactionType.BUY, "2026-01-02", stockId = 164, qty = "0.5", price = "280.10", currency = "USD"),
            tx(TransactionType.BUY, "2026-01-03", stockId = 164, qty = "0.25", price = "300.00", currency = "USD"),
        )
        val p = result.positions.single()
        assertThat(p.currency).isEqualTo("USD")
        assertThat(p.quantity).isEqualByComparingTo("0.75")
        assertThat(p.costBasis).isEqualByComparingTo("215.05")
        assertThat(p.avgCost).isEqualByComparingTo("286.733333")
        assertThat(result.cashByCurrency).containsOnlyKeys("USD")
    }

    @Test
    fun `stocks에 없는 상품은 코드로 묶는다`() {
        val p = calc(
            tx(TransactionType.BUY, "2026-01-02", stockId = null, code = "KR5223941K16", qty = "1000", price = "1.0234"),
        ).positions.single()
        assertThat(p.key).isEqualTo(InstrumentKey(null, "KR5223941K16"))
        assertThat(p.quantity).isEqualByComparingTo("1000")
    }

    @Test
    fun `수량은 끝자리 0을 지워 보여준다`() {
        val p = calc(tx(TransactionType.BUY, "2026-01-02", qty = "10.000000", price = "1")).positions.single()
        assertThat(p.quantity.toPlainString()).isEqualTo("10")
    }

    // ── ADR-111 매수일 환율 기준 원화 원가 ──
    private val rates = mapOf(LocalDate.parse("2025-03-03") to BigDecimal("1300"), LocalDate.parse("2025-04-01") to BigDecimal("1400"))
    private fun krwRate(currency: String, date: LocalDate): BigDecimal? = if (currency == "KRW") BigDecimal.ONE else rates[date]

    @Test
    fun `해외 종목 원화 원가는 매수일 환율로 쌓고, 매도는 같은 비율로 덜어 평균 매수 환율이 그대로다`() {
        val p = HoldingsCalculator.calculate(listOf(
            tx(TransactionType.BUY, "2025-03-03", stockId = 7, qty = "1", price = "100", currency = "USD"),
            tx(TransactionType.BUY, "2025-04-01", stockId = 7, qty = "1", price = "100", currency = "USD"),
            tx(TransactionType.SELL, "2025-04-01", stockId = 7, qty = "1", price = "120", currency = "USD"),
        ), ::krwRate).positions.single()
        assertThat(p.costBasis).isEqualByComparingTo("100")
        // 130,000 + 140,000 = 270,000 → 절반 매도 → 135,000(평균 1,350원)
        assertThat(p.costBasisKrw).isEqualByComparingTo("135000")
    }

    @Test
    fun `매수일 환율이 없으면 원화 원가는 null — 다 팔고 다시 사면 새로 시작한다`() {
        val missing = HoldingsCalculator.calculate(listOf(
            tx(TransactionType.BUY, "2025-01-10", stockId = 7, qty = "1", price = "100", currency = "USD"),
            tx(TransactionType.BUY, "2025-03-03", stockId = 7, qty = "1", price = "100", currency = "USD"),
        ), ::krwRate).positions.single()
        assertThat(missing.costBasisKrw).isNull()

        val reset = HoldingsCalculator.calculate(listOf(
            tx(TransactionType.BUY, "2025-01-10", stockId = 7, qty = "1", price = "100", currency = "USD"),
            tx(TransactionType.SELL, "2025-03-03", stockId = 7, qty = "1", price = "100", currency = "USD"),
            tx(TransactionType.BUY, "2025-04-01", stockId = 7, qty = "2", price = "50", currency = "USD"),
        ), ::krwRate).positions.single()
        assertThat(reset.costBasisKrw).isEqualByComparingTo("140000")
    }

    @Test
    fun `원화 종목은 원화 원가가 원가와 같고, 환율을 안 넘기면 채우지 않는다`() {
        val buy = tx(TransactionType.BUY, "2026-01-02", qty = "10", price = "70000", fee = "100")
        assertThat(HoldingsCalculator.calculate(listOf(buy), ::krwRate).positions.single().costBasisKrw).isEqualByComparingTo("700100")
        assertThat(calc(buy).positions.single().costBasisKrw).isNull()
    }
}
