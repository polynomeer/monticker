package com.monticker.api.portfolio.application

import com.monticker.api.paper.application.PaperPortfolioQueryService
import com.monticker.api.portfolio.domain.HoldingsCalculator
import com.monticker.api.portfolio.domain.InstrumentKey
import com.monticker.api.portfolio.domain.Position
import com.monticker.api.portfolio.infrastructure.PortfolioAccountRepository
import com.monticker.api.portfolio.infrastructure.PortfolioTransactionRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/** REAL — 모의계좌를 뺀 모든 계좌의 합. PAPER — 모의계좌만(모의 자금을 실제 보유와 섞지 않는다) */
enum class HoldingsScope { REAL, PAPER }

data class HoldingAccountShare(val accountId: Long?, val accountName: String, val quantity: BigDecimal)

data class HoldingView(
    val stockId: Long?,
    val instrumentCode: String?,
    val symbol: String?,
    val name: String?,
    val currency: String,
    val quantity: BigDecimal,
    val avgCost: BigDecimal?,
    val costBasis: BigDecimal,
    /** 시세가 없는 상품(stocks에 없는 펀드·채권 등)은 null */
    val currentPrice: BigDecimal?,
    val marketValue: BigDecimal?,
    val unrealizedPnl: BigDecimal?,
    val unrealizedPnlRate: Double?,
    val realizedPnl: BigDecimal,
    val dividendsNet: BigDecimal,
    /** 같은 통화 안에서 평가금액 비중(%). 시세가 없으면 null */
    val weight: Double?,
    val accounts: List<HoldingAccountShare>,
    /** 가진 것보다 많이 판 기록이 있다 — 앞 구간 거래가 빠졌을 수 있다 */
    val needsReview: Boolean,
)

/** 통화별 합계 — 환율 환산은 다음 단계(docs/account-aggregation-plan.md 1.5) */
data class CurrencyTotal(
    val currency: String,
    val costBasis: BigDecimal,
    /** 시세가 있는 종목의 평가금액 합 */
    val marketValue: BigDecimal,
    val unrealizedPnl: BigDecimal,
    /** 시세가 없어 평가금액에 들어가지 않은 종목 수 */
    val unpricedCount: Int,
)

data class HoldingsView(val scope: HoldingsScope, val holdings: List<HoldingView>, val totals: List<CurrencyTotal>)

/**
 * 통합 보유 조회(ADR-106). 계좌마다 원장으로 포지션을 계산하고([HoldingsCalculator]), 같은 종목·통화를 합친다.
 * 표시용이다 — 실계좌 리스크 검사·조건부 주문은 이 값을 쓰지 않는다(ADR-055).
 */
@Service
@Transactional(readOnly = true)
class PortfolioHoldingsQueryService(
    private val accounts: PortfolioAccountRepository,
    private val transactions: PortfolioTransactionRepository,
    private val paper: PaperPortfolioQueryService,
    private val jdbc: JdbcTemplate,
) {
    fun holdings(userId: Long, scope: HoldingsScope): HoldingsView = when (scope) {
        HoldingsScope.REAL -> real(userId)
        HoldingsScope.PAPER -> paper(userId)
    }

    /**
     * 모든 실계좌(모의계좌 제외)에서 지금 보유 중인 종목(stocks에 있는 것만). 보유 종목 피드(ADR-108)가 쓴다 —
     * worker의 알림 대상(`StockAudience`)과 같은 기준이다.
     */
    fun heldStockIds(userId: Long): Set<Long> =
        transactions.findAllForUser(userId).groupBy { it.accountId }.values
            .flatMap { HoldingsCalculator.calculate(it).positions }
            .filter { it.isOpen }
            .mapNotNullTo(LinkedHashSet()) { it.key.stockId }

    private fun real(userId: Long): HoldingsView {
        val accountNames = accounts.findAll(userId).associate { it.id to it.name }
        val byAccount = transactions.findAllForUser(userId).groupBy { it.accountId }

        // (종목, 통화) → 계좌별 열린 포지션
        val merged = LinkedHashMap<Pair<InstrumentKey, String>, MutableList<Pair<Long, Position>>>()
        for ((accountId, txs) in byAccount) {
            for (p in HoldingsCalculator.calculate(txs).positions) {
                if (p.isOpen) merged.getOrPut(p.key to p.currency) { mutableListOf() } += accountId to p
            }
        }
        val stockIds = merged.keys.mapNotNull { it.first.stockId }.distinct()
        val stocks = stockInfo(stockIds)
        val prices = latestPrices(stockIds)

        val rows = merged.map { (k, parts) ->
            val (key, currency) = k
            val quantity = parts.fold(BigDecimal.ZERO) { s, (_, p) -> s + p.quantity }
            val cost = parts.fold(BigDecimal.ZERO) { s, (_, p) -> s + p.costBasis }
            val stock = key.stockId?.let { stocks[it] }
            row(
                stockId = key.stockId, code = key.code, symbol = stock?.symbol,
                name = stock?.name ?: parts.firstNotNullOfOrNull { it.second.name }, currency = currency,
                quantity = quantity, cost = cost, price = key.stockId?.let { prices[it] },
                realized = parts.fold(BigDecimal.ZERO) { s, (_, p) -> s + p.realizedPnl },
                dividends = parts.fold(BigDecimal.ZERO) { s, (_, p) -> s + p.dividendsNet },
                shares = parts.map { (accountId, p) -> HoldingAccountShare(accountId, accountNames[accountId] ?: "", p.quantity) },
                needsReview = parts.any { it.second.oversold },
            )
        }
        return view(HoldingsScope.REAL, rows)
    }

    /** 모의계좌 — paper 모듈의 계산(portfolio_positions, ADR-012)을 그대로 쓴다 */
    private fun paper(userId: Long): HoldingsView {
        val holdings = paper.buildHoldings(userId)
        val currencies = stockInfo(holdings.map { it.stockId })
        val rows = holdings.map { h ->
            val quantity = BigDecimal(h.quantity)
            row(
                stockId = h.stockId, code = null, symbol = h.symbol, name = h.name,
                currency = currencies[h.stockId]?.currency ?: "KRW",
                quantity = quantity, cost = (h.avgPrice * quantity).setScale(4, RoundingMode.HALF_UP), price = h.currentPrice,
                realized = BigDecimal.ZERO, dividends = BigDecimal.ZERO,
                shares = listOf(HoldingAccountShare(null, PAPER_ACCOUNT_NAME, quantity)), needsReview = false,
            )
        }
        return view(HoldingsScope.PAPER, rows)
    }

    private fun row(
        stockId: Long?, code: String?, symbol: String?, name: String?, currency: String, quantity: BigDecimal, cost: BigDecimal,
        price: BigDecimal?, realized: BigDecimal, dividends: BigDecimal, shares: List<HoldingAccountShare>, needsReview: Boolean,
    ): HoldingView {
        val value = price?.let { (it * quantity).setScale(4, RoundingMode.HALF_UP) }
        val pnl = value?.let { it - cost }
        return HoldingView(
            stockId = stockId, instrumentCode = code, symbol = symbol, name = name, currency = currency,
            quantity = quantity, avgCost = if (quantity.signum() > 0) cost.divide(quantity, 6, RoundingMode.HALF_UP) else null,
            costBasis = cost, currentPrice = price, marketValue = value, unrealizedPnl = pnl,
            unrealizedPnlRate = pnl?.takeIf { cost.signum() > 0 }?.let { it.divide(cost, 8, RoundingMode.HALF_UP).toDouble() * 100 },
            realizedPnl = realized, dividendsNet = dividends, weight = null, accounts = shares, needsReview = needsReview,
        )
    }

    private fun view(scope: HoldingsScope, rows: List<HoldingView>): HoldingsView {
        val byCurrency = rows.groupBy { it.currency }
        val totals = byCurrency.map { (currency, list) ->
            val priced = list.filter { it.marketValue != null }
            CurrencyTotal(
                currency = currency,
                costBasis = list.fold(BigDecimal.ZERO) { s, h -> s + h.costBasis },
                marketValue = priced.fold(BigDecimal.ZERO) { s, h -> s + h.marketValue!! },
                unrealizedPnl = priced.fold(BigDecimal.ZERO) { s, h -> s + h.unrealizedPnl!! },
                unpricedCount = list.size - priced.size,
            )
        }
        val valueByCurrency = totals.associate { it.currency to it.marketValue }
        val withWeight = rows.map { h ->
            val total = valueByCurrency[h.currency]
            h.copy(weight = if (h.marketValue != null && total != null && total.signum() > 0) {
                h.marketValue.divide(total, 8, RoundingMode.HALF_UP).toDouble() * 100
            } else null)
        }.sortedWith(compareBy<HoldingView> { it.currency != "KRW" }.thenByDescending { it.marketValue ?: it.costBasis })
        return HoldingsView(scope, withWeight, totals.sortedBy { it.currency != "KRW" })
    }

    private data class StockRow(val symbol: String, val name: String, val currency: String)

    private fun stockInfo(ids: List<Long>): Map<Long, StockRow> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query(
            "SELECT id, symbol, name, currency FROM stocks WHERE id IN (${ids.joinToString(",") { "?" }})",
            { rs, _ -> rs.getLong("id") to StockRow(rs.getString("symbol"), rs.getString("name"), rs.getString("currency")) },
            *ids.toTypedArray(),
        ).toMap()
    }

    /** 모의계좌 보유 화면과 같은 기준 — 1분봉 최신 종가 */
    private fun latestPrices(ids: List<Long>): Map<Long, BigDecimal> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query(
            """SELECT DISTINCT ON (stock_id) stock_id, close FROM candles_1m
               WHERE stock_id IN (${ids.joinToString(",") { "?" }}) ORDER BY stock_id, candle_time DESC""",
            { rs, _ -> rs.getLong("stock_id") to rs.getBigDecimal("close") },
            *ids.toTypedArray(),
        ).toMap()
    }

    companion object {
        const val PAPER_ACCOUNT_NAME = "모의계좌"
    }
}
