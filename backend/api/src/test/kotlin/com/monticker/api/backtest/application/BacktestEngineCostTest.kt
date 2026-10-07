package com.monticker.api.backtest.application

import com.monticker.api.backtest.domain.BacktestRequest
import com.monticker.api.backtest.domain.CostModel
import com.monticker.api.backtest.domain.DailyCandle
import com.monticker.api.backtest.domain.StrategyType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

class BacktestEngineCostTest {

    private fun candle(day: Int, close: Double) = DailyCandle(
        date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
        open = BigDecimal(close), high = BigDecimal(close), low = BigDecimal(close),
        close = BigDecimal(close), volume = 1000L,
    )

    // 100 ×6 → 130에서 단기 MA(3)가 장기 MA(6)를 돌파해 매수, 끝까지 보유 후 기간 종료 청산
    private val candles = (List(6) { 100.0 } + listOf(130.0, 131.0, 132.0)).mapIndexed { i, c -> candle(i, c) }

    private fun request(costs: CostModel) = BacktestRequest(
        stockId = 1, strategy = StrategyType.MA_CROSSOVER,
        fromDate = candles.first().date, toDate = candles.last().date,
        initialCapital = 10_000_000.0, shortPeriod = 3, longPeriod = 6,
        stopLossPct = 50.0, takeProfitPct = 500.0, costs = costs,
    )

    @Test
    fun `no cost model keeps the previous frictionless result`() {
        val r = BacktestEngine.run(candles, request(CostModel.NONE), "005930")

        assertThat(r.trades).hasSize(1)
        assertThat(r.costs.totalCost).isEqualTo(0.0)
        val t = r.trades.single()
        assertThat(t.entryPrice).isEqualTo(130.0)
        assertThat(t.exitPrice).isEqualTo(132.0)
    }

    @Test
    fun `fees tax and slippage reduce the final capital and are reported`() {
        val free = BacktestEngine.run(candles, request(CostModel.NONE), "005930")
        val costly = BacktestEngine.run(candles, request(CostModel(commissionPct = 0.015, sellTaxPct = 0.18, slippagePct = 0.05)), "005930")

        assertThat(costly.finalCapital).isLessThan(free.finalCapital)
        assertThat(costly.costs.totalCost).isGreaterThan(0.0)
        assertThat(costly.costs.sellTaxApplied).isTrue()
        val t = costly.trades.single()
        assertThat(t.entryPrice).isCloseTo(130.0 * 1.0005, within(1e-9))
        assertThat(t.exitPrice).isCloseTo(132.0 * 0.9995, within(1e-9))
        // 자본 감소분 = 보고된 누적 비용
        assertThat(free.finalCapital - costly.finalCapital).isCloseTo(costly.costs.totalCost, within(costly.trades.single().entryPrice * 2))
    }

    @Test
    fun `sell tax is not applied to overseas stocks`() {
        val r = BacktestEngine.run(candles, request(CostModel(sellTaxPct = 0.18)), "AAPL", domestic = false)

        assertThat(r.costs.sellTaxApplied).isFalse()
        assertThat(r.costs.sellTaxPct).isEqualTo(0.0)
        assertThat(r.costs.totalCost).isEqualTo(0.0)
    }

    @Test
    fun `out of range cost assumptions are rejected`() {
        assertThrows<IllegalArgumentException> { CostModel(commissionPct = -0.1) }
        assertThrows<IllegalArgumentException> { CostModel(sellTaxPct = 5.0) }
        assertThrows<IllegalArgumentException> { CostModel(slippagePct = Double.NaN) }
    }
}
