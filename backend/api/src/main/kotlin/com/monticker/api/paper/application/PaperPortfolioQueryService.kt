package com.monticker.api.paper.application

import com.monticker.api.common.domain.Price
import com.monticker.api.paper.infrastructure.PaperAccountRepository
import com.monticker.api.paper.infrastructure.PaperTradeRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId

@Service
@Transactional(readOnly = true)
class PaperPortfolioQueryService(
    private val accountRepo: PaperAccountRepository,
    private val tradeRepo: PaperTradeRepository,
    private val jdbc: JdbcTemplate,
) {
    private fun currentPriceMap(stockIds: List<Long>): Map<Long, BigDecimal> {
        if (stockIds.isEmpty()) return emptyMap()
        return jdbc.query(
            """SELECT DISTINCT ON (stock_id) stock_id, close
               FROM candles_1m WHERE stock_id IN (${stockIds.joinToString(",") { "?" }})
               ORDER BY stock_id, candle_time DESC""",
            { rs, _ -> rs.getLong("stock_id") to rs.getBigDecimal("close") },
            *stockIds.toTypedArray(),
        ).toMap()
    }

    private fun stockInfoMap(stockIds: List<Long>): Map<Long, Pair<String, String>> {
        if (stockIds.isEmpty()) return emptyMap()
        return jdbc.query(
            "SELECT id, symbol, name FROM stocks WHERE id IN (${stockIds.joinToString(",") { "?" }})",
            { rs, _ -> Triple(rs.getLong("id"), rs.getString("symbol"), rs.getString("name")) },
            *stockIds.toTypedArray(),
        ).associate { it.first to (it.second to it.third) }
    }

    fun buildHoldings(userId: Long): List<HoldingResponse> {
        // CQRS 읽기모델: paper_trades 집계 대신 portfolio_positions를 직접 읽는다.
        val positions = jdbc.query(
            "SELECT stock_id, net_qty, avg_buy_price FROM portfolio_positions WHERE user_id = ? AND net_qty > 0",
            { rs, _ -> Triple(rs.getLong("stock_id"), rs.getInt("net_qty"), rs.getBigDecimal("avg_buy_price")) },
            userId,
        )
        if (positions.isEmpty()) return emptyList()

        val stockIds = positions.map { it.first }
        val priceMap = currentPriceMap(stockIds)
        val infoMap  = stockInfoMap(stockIds)
        val entryMap = latestEntryOrigins(userId, stockIds)

        return positions.mapNotNull { (stockId, qty, avgPrice) ->
            val cur = priceMap[stockId] ?: return@mapNotNull null
            val (symbol, name) = infoMap[stockId] ?: return@mapNotNull null
            val value   = cur.multiply(BigDecimal(qty))
            val cost    = avgPrice.multiply(BigDecimal(qty))
            val pnl     = value - cost
            val pnlRate = if (cost > BigDecimal.ZERO)
                pnl.divide(cost, 6, RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble() else 0.0
            val entry = entryMap[stockId]
            HoldingResponse(stockId, symbol, name, qty, avgPrice, cur, value, pnl, pnlRate,
                entryOrigin = entry?.first, entryOriginRef = entry?.second)
        }
    }

    /**
     * ADR-085 — 종목별 가장 최근 매수 체결의 진입 출처(한 번의 쿼리). 출처를 판정할 수 없던 거래(V82 백필 불가)는
     * origin이 null이고 화면에 "—"로 보인다. 매수가 없는 종목은 결과에 없다.
     *
     * 종목마다 `LATERAL … LIMIT 1`로 V98 인덱스(user_id, stock_id, traded_at DESC, id DESC)를 한 번씩 짚는다.
     * 예전 `DISTINCT ON (stock_id) … stock_id IN (…)`은 TimescaleDB SkipScan이 같은 인덱스를 고르면서 `stock_id IN`을
     * 필터로 돌려 사용자 범위 전체를 읽었다(docs/data-model.md). 정렬·동률 규칙(traded_at DESC, id DESC)은 그대로다.
     */
    private fun latestEntryOrigins(userId: Long, stockIds: List<Long>): Map<Long, Pair<String?, Long?>> {
        if (stockIds.isEmpty()) return emptyMap()
        val ids = stockIds.distinct()
        return jdbc.query(
            """SELECT s.stock_id, t.origin, t.origin_ref
               FROM unnest(?::bigint[]) AS s(stock_id)
               CROSS JOIN LATERAL (
                   SELECT origin, origin_ref
                   FROM paper_trades
                   WHERE user_id = ? AND stock_id = s.stock_id AND side = 'BUY'
                   ORDER BY traded_at DESC, id DESC
                   LIMIT 1
               ) t""",
            PreparedStatementSetter { ps ->
                ps.setArray(1, ps.connection.createArrayOf("bigint", ids.toTypedArray()))
                ps.setLong(2, userId)
            },
            { rs, _ -> rs.getLong("stock_id") to (rs.getString("origin") to (rs.getObject("origin_ref") as Number?)?.toLong()) },
        ).toMap()
    }

    fun getPortfolio(userId: Long): PortfolioResponse {
        val account   = accountRepo.findByUserId(userId).orElseGet {
            com.monticker.api.paper.domain.PaperAccount(userId = userId)
        }
        val holdings  = buildHoldings(userId)
        val evalValue = holdings.fold(BigDecimal.ZERO) { acc, h -> acc + h.value }
        val totalValue = account.cash.amount + evalValue
        val invested   = holdings.fold(BigDecimal.ZERO) { acc, h -> acc + h.avgPrice.multiply(BigDecimal(h.quantity)) }
        val pnl        = evalValue - invested
        val pnlRate    = if (invested > BigDecimal.ZERO)
            pnl.divide(invested, 6, RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble() else 0.0
        return PortfolioResponse(account.cash.amount, totalValue, pnl, pnlRate, holdings)
    }

    /**
     * 거래 내역 + 진입 경로 + 감정 태그 — 한 번의 쿼리.
     * 경로는 ADR-085의 paper_trades.origin(서버가 주문 제출 경로에서 정해 체결로 옮긴 값)이다. 예전엔 매칭 주문의
     * 멱등 키 접두사에서 읽었는데, 그 키는 /api/matching/orders 요청 본문으로 위조할 수 있었다.
     * 감정 태그는 wallet 모듈의 테이블이지만 화면 하나를 위해 거래마다 따로 부르던 N+1을 없애려고 여기서 조인한다
     * (읽기 전용 — 태그 쓰기는 wallet EmotionTagService만). 태그 소유자도 거래 소유자와 같아야 한다(예전 IDOR 잔여 행 차단).
     */
    fun getHistory(
        userId: Long,
        page: Int = 0,
        size: Int = 20,
        filter: TradeHistoryFilter = TradeHistoryFilter.NONE,
    ): List<TradeHistoryResponse> {
        data class Row(
            val id: Long, val side: String, val stockId: Long, val quantity: Int,
            val price: BigDecimal, val amount: BigDecimal, val tradedAt: Instant,
            val origin: String?, val originRef: Long?, val orderType: String?,
            val emotion: String?, val emotionMemo: String?,
        )
        val trades = jdbc.query(
            """SELECT pt.id, pt.side, pt.stock_id, pt.quantity, pt.price, pt.amount, pt.traded_at,
                      pt.origin, pt.origin_ref, o.order_type, et.emotion, et.memo
               FROM paper_trades pt
               LEFT JOIN fills f  ON f.id = pt.fill_id
               LEFT JOIN orders o ON o.id = f.order_id
               LEFT JOIN order_emotion_tags et ON et.paper_trade_id = pt.id AND et.user_id = pt.user_id
               WHERE pt.user_id = ?${filter.sql()} ORDER BY pt.traded_at DESC, pt.id DESC LIMIT ? OFFSET ?""",
            { rs, _ -> Row(
                id        = rs.getLong("id"),
                side      = rs.getString("side"),
                stockId   = rs.getLong("stock_id"),
                quantity  = rs.getInt("quantity"),
                price     = rs.getBigDecimal("price"),
                amount    = rs.getBigDecimal("amount"),
                tradedAt  = rs.getTimestamp("traded_at").toInstant(),
                origin    = rs.getString("origin"),
                originRef = (rs.getObject("origin_ref") as Number?)?.toLong(),
                orderType = rs.getString("order_type"),
                emotion   = rs.getString("emotion"),
                emotionMemo = rs.getString("memo"),
            ) },
            userId, *filter.args(), size, page.toLong() * size,
        )
        if (trades.isEmpty()) return emptyList()
        val infoMap = stockInfoMap(trades.map { it.stockId }.distinct())
        return trades.mapNotNull { t ->
            val (symbol, name) = infoMap[t.stockId] ?: return@mapNotNull null
            TradeHistoryResponse(
                t.id, t.side, t.stockId, symbol, name, t.quantity, t.price, t.amount, t.tradedAt,
                source = t.origin, originRef = t.originRef,
                watchRuleId = t.originRef.takeIf { t.origin == "WATCH_RULE" },
                conditionalOrderId = t.originRef.takeIf { t.origin == "CONDITIONAL" },
                orderType = t.orderType ?: "MARKET",
                emotion = t.emotion, emotionMemo = t.emotionMemo,
            )
        }
    }

    fun getRiskMetrics(userId: Long): RiskMetricsResponse {
        val account  = accountRepo.findByUserId(userId).orElseGet {
            com.monticker.api.paper.domain.PaperAccount(userId = userId)
        }
        val trades   = tradeRepo.findTop20ByUserIdOrderByTradedAtDesc(userId)
        val holdings = buildHoldings(userId)

        if (holdings.isEmpty() && trades.isEmpty()) {
            return RiskMetricsResponse(
                sharpeRatio = 0.0, beta = 1.0, maxDrawdown = 0.0,
                volatility = 0.0, var95 = 0.0,
                winRate = 0.0, totalTrades = 0, avgReturn = 0.0,
                dailyReturns = emptyList(), drawdownSeries = emptyList(),
                hasEnoughData = false,
                message = "거래 내역이 없습니다. 먼저 모의 투자를 시작해보세요.",
            )
        }

        val stockIds = (holdings.map { it.stockId } + trades.map { it.stockId }).distinct()
        if (stockIds.isEmpty()) return RiskMetricsResponse(
            0.0, 1.0, 0.0, 0.0, 0.0, 0.0, trades.size, 0.0,
            emptyList(), emptyList(), false, "종목 데이터 없음",
        )

        val placeholders = stockIds.joinToString(",") { "?" }
        // candles_1d의 "오늘" 행은 장중 계속 바뀌는 미확정 값이라 샤프비율/변동성 계산에서 제외한다.
        val todayStartKst = Instant.now().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()
        val candles = jdbc.query(
            """SELECT stock_id, DATE(candle_time AT TIME ZONE 'Asia/Seoul') AS d, close
               FROM candles_1d WHERE stock_id IN ($placeholders) AND candle_time < ? ORDER BY d, stock_id""",
            { rs, _ -> Triple(rs.getLong("stock_id"), rs.getDate("d").toLocalDate(), rs.getBigDecimal("close").toDouble()) },
            *stockIds.toTypedArray(), java.sql.Timestamp.from(todayStartKst),
        )

        val priceMap = candles.groupBy { it.second }
            .mapValues { (_, v) -> v.associate { it.first to it.third } }
        val dates = priceMap.keys.sorted()

        if (dates.size < 5) {
            return RiskMetricsResponse(
                sharpeRatio = 0.0, beta = 1.0, maxDrawdown = 0.0,
                volatility = 0.0, var95 = 0.0,
                winRate = 0.0, totalTrades = trades.size, avgReturn = 0.0,
                dailyReturns = emptyList(), drawdownSeries = emptyList(),
                hasEnoughData = false,
                message = "수익률 계산 데이터 부족 (${dates.size}일, 최소 5일 필요)",
            )
        }

        val portfolioPrices  = dates.map { d -> priceMap[d]?.values?.average() ?: 0.0 }
        val portfolioReturns = portfolioPrices.zipWithNext { a, b -> if (a == 0.0) 0.0 else (b - a) / a }

        val initial     = account.cash.amount.toDouble() + holdings.sumOf { it.value.toDouble() }
            .coerceAtLeast(account.cash.amount.toDouble())
        var equity      = initial
        val equityCurve = mutableListOf(initial)
        portfolioReturns.forEach { r -> equity *= (1 + r); equityCurve.add(equity) }

        // Win rate — batch AVG lookup
        val sellTrades = trades.filter { it.side == "SELL" }
        val avgBuyPriceMap: Map<Long, BigDecimal> = if (sellTrades.isNotEmpty()) {
            val sellStockIds = sellTrades.map { it.stockId }.distinct()
            jdbc.query(
                """SELECT stock_id, AVG(price) AS avg_price FROM paper_trades
                   WHERE user_id = ? AND side = 'BUY'
                   AND stock_id IN (${sellStockIds.joinToString(",") { "?" }})
                   GROUP BY stock_id""",
                { rs, _ -> rs.getLong("stock_id") to rs.getBigDecimal("avg_price") },
                userId, *sellStockIds.toTypedArray(),
            ).toMap()
        } else emptyMap()

        val wins    = sellTrades.count { t -> t.price > (avgBuyPriceMap[t.stockId] ?: t.price) }
        val winRate = if (sellTrades.isNotEmpty()) wins.toDouble() / sellTrades.size * 100 else 0.0

        val dailyReturnsList = dates.drop(1).zip(portfolioReturns) { d, r -> DailyReturn(d.toString(), r * 100) }
        val ddSeries = dates.zip(RiskCalculator.drawdownSeries(equityCurve)) { d, dd -> DrawdownPoint(d.toString(), dd) }

        return RiskMetricsResponse(
            sharpeRatio  = RiskCalculator.sharpe(portfolioReturns),
            beta         = RiskCalculator.beta(portfolioReturns, portfolioReturns),
            maxDrawdown  = RiskCalculator.maxDrawdown(equityCurve),
            volatility   = RiskCalculator.annualizedVolatility(portfolioReturns),
            var95        = RiskCalculator.var95(portfolioReturns),
            winRate      = winRate,
            totalTrades  = trades.size,
            avgReturn    = if (portfolioReturns.isNotEmpty()) portfolioReturns.average() * 100 else 0.0,
            dailyReturns = dailyReturnsList,
            drawdownSeries = ddSeries,
            hasEnoughData  = true,
            message        = null,
        )
    }
}

/**
 * 거래 내역 조건 — 종목·체결 시각 구간(from 포함, to 제외). 사용자 범위(user_id)는 조건과 무관하게 항상 걸린다.
 * 값은 바인딩 파라미터로만 들어가고 SQL 조각은 고정 문자열이다.
 */
data class TradeHistoryFilter(
    val stockId: Long? = null,
    val from: Instant? = null,
    val to: Instant? = null,
) {
    init {
        require(stockId == null || stockId > 0) { "stockId는 양수여야 합니다" }
        require(from == null || to == null || from.isBefore(to)) { "from은 to보다 앞서야 합니다" }
    }

    internal fun sql(): String = buildString {
        if (stockId != null) append(" AND pt.stock_id = ?")
        if (from != null) append(" AND pt.traded_at >= ?")
        if (to != null) append(" AND pt.traded_at < ?")
    }

    /** Instant는 JdbcTemplate에 바로 넘기지 않는다(드라이버 타입 추론 문제) — Timestamp로 감싼다. */
    internal fun args(): Array<Any> = listOfNotNull<Any>(
        stockId,
        from?.let { java.sql.Timestamp.from(it) },
        to?.let { java.sql.Timestamp.from(it) },
    ).toTypedArray()

    companion object {
        val NONE = TradeHistoryFilter()
    }
}
