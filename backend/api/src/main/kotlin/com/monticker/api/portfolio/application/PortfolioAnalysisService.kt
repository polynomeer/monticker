package com.monticker.api.portfolio.application

import com.monticker.api.portfolio.domain.AccountKind
import com.monticker.api.portfolio.domain.HoldingsCalculator
import com.monticker.api.portfolio.domain.InstrumentKey
import com.monticker.api.portfolio.domain.PortfolioTransaction
import com.monticker.api.portfolio.domain.TransactionType
import com.monticker.api.portfolio.domain.analysis.CashFlow
import com.monticker.api.portfolio.domain.analysis.Xirr
import com.monticker.api.portfolio.infrastructure.KrwRateBook
import com.monticker.api.portfolio.infrastructure.KrwRates
import com.monticker.api.portfolio.infrastructure.PortfolioAccountRepository
import com.monticker.api.portfolio.infrastructure.PortfolioTransactionRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class FxRateView(val currency: String, val krwRate: BigDecimal, val asOf: Instant?, val mocked: Boolean)

/**
 * 원화로 합친 요약. 환산할 수 없는 통화([excludedCurrencies])와 시세 없는 종목은 빠진다.
 * 원가는 **매수한 날의 환율**로 바꾼 값이라, 평가손익 = 주가 손익([pricePnlKrw]) + 환차손익([fxPnlKrw])으로 나뉜다.
 * 매수일 환율을 모르는 종목이 있으면 원가·환차손익은 null이다(평가금액·주가 손익은 지금 환율로 낸다).
 */
data class KrwSummary(
    val marketValueKrw: BigDecimal,
    val costBasisKrw: BigDecimal?,
    val unrealizedPnlKrw: BigDecimal?,
    val pricePnlKrw: BigDecimal,
    val fxPnlKrw: BigDecimal?,
    val excludedCurrencies: List<String>,
    val unpricedCount: Int,
    val fxMocked: Boolean,
)

enum class AllocationDimension { SECTOR, COUNTRY, CURRENCY, ACCOUNT_KIND, INSTITUTION }

data class AllocationSlice(val key: String, val valueKrw: BigDecimal, val weight: Double)

data class AllocationView(val dimension: AllocationDimension, val slices: List<AllocationSlice>)

data class DividendMonth(val month: String, val byCurrency: Map<String, BigDecimal>, val krw: BigDecimal?)

data class DividendStock(val stockId: Long?, val instrumentCode: String?, val name: String?, val currency: String, val trailing12m: BigDecimal)

data class DividendSummary(
    /** 최근 12개월 받은 배당(세후) 원화 합계 — 환산할 수 없는 배당이 있으면 null */
    val trailing12mKrw: BigDecimal?,
    /** 최근 12개월 배당 ÷ 지금 보유의 원화 원가(%) */
    val yieldOnCost: Double?,
    /** 이번 달을 포함한 최근 12개월, 오래된 달부터 */
    val months: List<DividendMonth>,
    val topStocks: List<DividendStock>,
)

/**
 * 돈 가중 수익률(XIRR, 연환산) — 통화별로 따로 낸다. 흐름은 매수(−)·매도(+)·배당(+)·수수료·세금(−)이고 지금 평가금액을 오늘의 (+)로 둔다.
 * 입금·출금은 넣지 않는다(투자한 돈 기준, 예수금 제외). [reason]이 있으면 [xirr]은 null이다.
 */
data class ReturnView(
    val currency: String,
    val xirr: Double?,
    val invested: BigDecimal,
    val returned: BigDecimal,
    val currentValue: BigDecimal,
    /** (받은 돈 + 지금 평가금액) ÷ 낸 돈 */
    val multiple: Double?,
    val since: LocalDate?,
    val reason: String?,
)

data class PortfolioAnalysis(
    val fx: List<FxRateView>,
    val krw: KrwSummary,
    val allocations: List<AllocationView>,
    val dividends: DividendSummary,
    val returns: List<ReturnView>,
)

/**
 * 통합 포트폴리오 분석(ADR-106 1.5단계, ADR-111) — 모든 실계좌 기준, 모의계좌 제외. 표시용이다(실주문·리스크 검사에 쓰지 않는다).
 */
@Service
@Transactional(readOnly = true)
class PortfolioAnalysisService(
    private val accounts: PortfolioAccountRepository,
    private val transactions: PortfolioTransactionRepository,
    private val holdings: PortfolioHoldingsQueryService,
    private val rates: KrwRates,
    private val jdbc: JdbcTemplate,
) {
    /** 테스트에서 "오늘"을 고정한다 */
    var clock: Clock = Clock.system(KST)

    fun analysis(userId: Long): PortfolioAnalysis {
        val today = LocalDate.now(clock)
        val book = rates.load()
        val accountsById = accounts.findAll(userId).associateBy { it.id }
        val txs = transactions.findAllForUser(userId)
        val view = holdings.holdings(userId, HoldingsScope.REAL)

        // (종목, 통화) → 매수일 환율 기준 원화 원가 — 계좌마다 계산해 합친다. 하나라도 모르면 null
        val costKrw = HashMap<Pair<InstrumentKey, String>, BigDecimal?>()
        txs.groupBy { it.accountId }.values.forEach { accountTxs ->
            HoldingsCalculator.calculate(accountTxs) { currency, date -> book.on(currency, date) }.positions.filter { it.isOpen }.forEach { p ->
                val k = p.key to p.currency
                val seenUnknown = costKrw.containsKey(k) && costKrw[k] == null
                costKrw[k] = if (seenUnknown || p.costBasisKrw == null) null else (costKrw[k] ?: BigDecimal.ZERO) + p.costBasisKrw
            }
        }

        val rows = view.holdings
        val excluded = rows.map { it.currency }.filter { book.now(it) == null }.distinct().sorted()
        val priced = rows.filter { it.marketValue != null && book.now(it.currency) != null }

        var value = BigDecimal.ZERO
        var pricePnl = BigDecimal.ZERO
        var cost: BigDecimal? = BigDecimal.ZERO
        for (h in priced) {
            val now = book.now(h.currency)!!
            value += h.marketValue!! * now
            pricePnl += h.unrealizedPnl!! * now
            val c = costKrw[InstrumentKey(h.stockId, h.instrumentCode) to h.currency]
            cost = if (cost == null || c == null) null else cost + c
        }
        val unrealized = cost?.let { value - it }
        val krw = KrwSummary(
            marketValueKrw = value.money(), costBasisKrw = cost?.money(), unrealizedPnlKrw = unrealized?.money(),
            pricePnlKrw = pricePnl.money(), fxPnlKrw = unrealized?.let { (it - pricePnl).money() },
            excludedCurrencies = excluded, unpricedCount = rows.count { it.marketValue == null },
            fxMocked = book.mocked && rows.any { it.currency != KrwRateBook.KRW },
        )

        return PortfolioAnalysis(
            fx = book.current.map { (c, r) -> FxRateView(c, r.rate, r.asOf, r.mocked) },
            krw = krw,
            allocations = allocations(priced, book, accountsById.mapValues { it.value.accountKind }, accountsById.mapValues { it.value.institution.name }),
            dividends = dividends(txs, book, today, cost),
            returns = returns(txs, rows, today),
        )
    }

    private fun allocations(
        priced: List<HoldingView>, book: KrwRateBook, kinds: Map<Long, AccountKind>, institutions: Map<Long, String>,
    ): List<AllocationView> {
        val stocks = stockAttrs(priced.mapNotNull { it.stockId })
        fun slices(entries: List<Pair<String, BigDecimal>>): List<AllocationSlice> {
            val total = entries.fold(BigDecimal.ZERO) { s, e -> s + e.second }
            return entries.groupBy({ it.first }, { it.second })
                .map { (k, v) -> k to v.fold(BigDecimal.ZERO, BigDecimal::add) }
                .sortedByDescending { it.second }
                .map { (k, v) -> AllocationSlice(k, v.money(), if (total.signum() > 0) v.divide(total, 8, RoundingMode.HALF_UP).toDouble() * 100 else 0.0) }
        }
        val valued = priced.map { it to it.marketValue!! * book.now(it.currency)!! }
        // 계좌 기준 배분은 종목 평가금액을 계좌별 수량 비율로 나눈다
        fun byAccount(attr: (Long) -> String?) = valued.flatMap { (h, v) ->
            h.accounts.mapNotNull { share ->
                val id = share.accountId ?: return@mapNotNull null
                (attr(id) ?: OTHER) to (if (h.quantity.signum() > 0) v * share.quantity / h.quantity else BigDecimal.ZERO)
            }
        }
        return listOf(
            AllocationView(AllocationDimension.SECTOR, slices(valued.map { (h, v) -> (h.stockId?.let { stocks[it]?.sector } ?: UNCLASSIFIED) to v })),
            AllocationView(AllocationDimension.COUNTRY, slices(valued.map { (h, v) -> (h.stockId?.let { stocks[it]?.country } ?: OTHER) to v })),
            AllocationView(AllocationDimension.CURRENCY, slices(valued.map { (h, v) -> h.currency to v })),
            AllocationView(AllocationDimension.ACCOUNT_KIND, slices(byAccount { kinds[it]?.name })),
            AllocationView(AllocationDimension.INSTITUTION, slices(byAccount { institutions[it] })),
        )
    }

    private fun dividends(txs: List<PortfolioTransaction>, book: KrwRateBook, today: LocalDate, costKrw: BigDecimal?): DividendSummary {
        val first = YearMonth.from(today).minusMonths(11)
        val recent = txs.filter { it.type == TransactionType.DIVIDEND && !YearMonth.from(it.tradeDate).isBefore(first) && !it.tradeDate.isAfter(today) }
        fun net(t: PortfolioTransaction) = t.amount - t.tax - t.fee
        fun krwOf(list: List<PortfolioTransaction>): BigDecimal? =
            list.fold(BigDecimal.ZERO as BigDecimal?) { s, t -> book.on(t.currency, t.tradeDate)?.let { r -> s?.plus(net(t) * r) } }

        val months = (0L..11L).map { first.plusMonths(it) }.map { m ->
            val inMonth = recent.filter { YearMonth.from(it.tradeDate) == m }
            DividendMonth(
                month = m.toString(),
                byCurrency = inMonth.groupBy { it.currency }.mapValues { (_, l) -> l.fold(BigDecimal.ZERO) { s, t -> s + net(t) }.money() },
                krw = krwOf(inMonth)?.money(),
            )
        }
        val trailing = krwOf(recent)
        val names = stockNames(recent.mapNotNull { it.stockId }.distinct())
        val top = recent.groupBy { Triple(it.stockId, if (it.stockId == null) it.instrumentCode else null, it.currency) }
            .map { (k, l) ->
                DividendStock(k.first, k.second, k.first?.let { names[it] } ?: l.firstNotNullOfOrNull { it.instrumentName } ?: k.second,
                    k.third, l.fold(BigDecimal.ZERO) { s, t -> s + net(t) }.money())
            }
            .sortedByDescending { it.trailing12m * (book.now(it.currency) ?: BigDecimal.ZERO) }
            .take(10)
        return DividendSummary(
            trailing12mKrw = trailing?.money(),
            yieldOnCost = if (trailing != null && costKrw != null && costKrw.signum() > 0) trailing.divide(costKrw, 8, RoundingMode.HALF_UP).toDouble() * 100 else null,
            months = months,
            topStocks = top,
        )
    }

    private fun returns(txs: List<PortfolioTransaction>, rows: List<HoldingView>, today: LocalDate): List<ReturnView> =
        txs.filter { it.type != TransactionType.DEPOSIT && it.type != TransactionType.WITHDRAWAL }
            .groupBy { it.currency }
            .toSortedMap(compareBy<String> { it != KrwRateBook.KRW }.thenBy { it })
            .map { (currency, list) ->
                val flows = list.map { t ->
                    val v = when (t.type) {
                        TransactionType.BUY -> -(t.amount + t.fee + t.tax)
                        TransactionType.SELL -> t.amount - t.fee - t.tax
                        TransactionType.DIVIDEND -> t.amount - t.tax - t.fee
                        else -> -(t.amount + t.fee + t.tax)                     // FEE·TAX
                    }
                    t.tradeDate to v
                }
                val invested = flows.filter { it.second.signum() < 0 }.fold(BigDecimal.ZERO) { s, f -> s - f.second }
                val returned = flows.filter { it.second.signum() > 0 }.fold(BigDecimal.ZERO) { s, f -> s + f.second }
                val held = rows.filter { it.currency == currency }
                val current = held.mapNotNull { it.marketValue }.fold(BigDecimal.ZERO, BigDecimal::add)
                val unpriced = held.any { it.marketValue == null }
                val since = flows.minOfOrNull { it.first }
                val xirr = if (unpriced) null else Xirr.of(flows.map { CashFlow(it.first, it.second.toDouble()) } + CashFlow(today, current.toDouble()))
                ReturnView(
                    currency = currency, xirr = xirr?.let { it * 100 }, invested = invested.money(), returned = returned.money(),
                    currentValue = current.money(),
                    multiple = if (!unpriced && invested.signum() > 0) (returned + current).divide(invested, 8, RoundingMode.HALF_UP).toDouble() else null,
                    since = since,
                    reason = when {
                        unpriced -> "시세가 없는 종목이 있어 계산하지 않습니다"
                        xirr == null && since != null && java.time.temporal.ChronoUnit.DAYS.between(since, today) < Xirr.MIN_DAYS -> "기간이 ${Xirr.MIN_DAYS}일보다 짧습니다"
                        xirr == null -> "계산할 수 있는 흐름이 없습니다"
                        else -> null
                    },
                )
            }

    private data class StockAttr(val sector: String?, val country: String)

    private fun stockAttrs(ids: List<Long>): Map<Long, StockAttr> {
        if (ids.isEmpty()) return emptyMap()
        val distinct = ids.distinct()
        return jdbc.query(
            "SELECT id, sector, country FROM stocks WHERE id IN (${distinct.joinToString(",") { "?" }})",
            { rs, _ -> rs.getLong("id") to StockAttr(rs.getString("sector")?.takeIf { it.isNotBlank() }, rs.getString("country")) },
            *distinct.toTypedArray(),
        ).toMap()
    }

    private fun stockNames(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query(
            "SELECT id, name FROM stocks WHERE id IN (${ids.joinToString(",") { "?" }})",
            { rs, _ -> rs.getLong("id") to rs.getString("name") },
            *ids.toTypedArray(),
        ).toMap()
    }

    private fun BigDecimal.money(): BigDecimal = setScale(4, RoundingMode.HALF_UP)

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
        const val UNCLASSIFIED = "UNCLASSIFIED"
        const val OTHER = "OTHER"
    }
}
