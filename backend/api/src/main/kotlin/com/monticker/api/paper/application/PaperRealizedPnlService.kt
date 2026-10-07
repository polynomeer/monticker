package com.monticker.api.paper.application

import com.monticker.api.matching.submit.OrderOriginType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** 실현 손익 계산 입력 — 한 종목의 체결을 시간순으로. */
data class PnlTradeLine(val id: Long, val stockId: Long, val side: String, val quantity: Int, val amount: BigDecimal)

/** 매도 체결 한 건의 실현 손익. [costBasis] = 그 시점 평균 매수단가 × 매도 수량. */
data class RealizedPnl(val pnl: BigDecimal, val costBasis: BigDecimal) {
    val pnlPct: Double?
        get() = if (costBasis.signum() > 0)
            pnl.divide(costBasis, 6, RoundingMode.HALF_UP).multiply(BigDecimal(100)).toDouble() else null
}

/**
 * 이동평균법 실현 손익 — `PortfolioPositionProjection`과 같은 규칙이다(보유 화면의 평균단가와 숫자가 맞는다).
 * 매수: 수량·원가를 더한다. 매도: 그 시점 평균단가(원가 ÷ 수량)로 원가를 덜어내고, 매도금액 − 덜어낸 원가가 실현 손익.
 * 보유가 0인데 매도가 보이면(초기화 이전 데이터 등) 손익을 정할 수 없어 결과에서 뺀다.
 */
object RealizedPnlCalculator {
    fun compute(lines: List<PnlTradeLine>): Map<Long, RealizedPnl> {
        data class Pos(var qty: Long = 0, var cost: BigDecimal = BigDecimal.ZERO)
        val pos = HashMap<Long, Pos>()
        val out = LinkedHashMap<Long, RealizedPnl>()
        for (t in lines) {
            val p = pos.getOrPut(t.stockId) { Pos() }
            if (t.side == "BUY") {
                p.qty += t.quantity
                p.cost += t.amount
                continue
            }
            if (p.qty <= 0) continue
            val q = minOf(t.quantity.toLong(), p.qty)
            val avg = p.cost.divide(BigDecimal(p.qty), 8, RoundingMode.HALF_UP)
            val basis = avg.multiply(BigDecimal(q)).setScale(4, RoundingMode.HALF_UP)
            // 보유보다 많이 판 줄(데이터 불일치)은 보유분만큼만 손익으로 본다 — 매도금액도 같은 비율로
            val proceeds = if (q == t.quantity.toLong()) t.amount
                else t.amount.multiply(BigDecimal(q)).divide(BigDecimal(t.quantity), 4, RoundingMode.HALF_UP)
            out[t.id] = RealizedPnl(proceeds.subtract(basis).setScale(4, RoundingMode.HALF_UP), basis)
            p.qty -= q
            p.cost = if (p.qty == 0L) BigDecimal.ZERO else (p.cost - basis).max(BigDecimal.ZERO)
        }
        return out
    }
}

data class OriginRefPnl(val originRef: Long?, val realizedPnl: BigDecimal, val sellCount: Int, val tradeCount: Int)

data class OriginPnlResponse(
    val origin: String,
    /** 이 출처의 매도 체결 실현 손익 합(원). 매도가 없으면 0이고 [sellCount]가 0이다. */
    val totalRealizedPnl: BigDecimal,
    val sellCount: Int,
    /** 이 출처의 체결 수(매수 + 매도) */
    val tradeCount: Int,
    val byRef: List<OriginRefPnl>,
)

/**
 * ADR-085 — 출처별 실현 손익. 매도 체결의 출처로 귀속한다: "Watch Rule #3 경유 손익" = 규칙 #3이 낸 **매도** 체결의
 * 실현 손익 합이다. 규칙이 매수만 했고 사용자가 직접 팔았다면 그 손익은 MANUAL 쪽에 잡힌다(로트 추적을 하지 않는다).
 */
@Service
@Transactional(readOnly = true)
class PaperRealizedPnlService(private val jdbc: JdbcTemplate) {

    fun byOrigin(userId: Long, origin: OrderOriginType): OriginPnlResponse {
        val originTrades = jdbc.query(
            "SELECT id, stock_id, side, origin_ref FROM paper_trades WHERE user_id = ? AND origin = ?",
            { rs, _ -> OriginTrade(rs.getLong("id"), rs.getLong("stock_id"), rs.getString("side"), (rs.getObject("origin_ref") as Number?)?.toLong()) },
            userId, origin.name,
        )
        if (originTrades.isEmpty()) return OriginPnlResponse(origin.name, BigDecimal.ZERO, 0, 0, emptyList())

        val sellStocks = originTrades.filter { it.side == "SELL" }.map { it.stockId }.distinct()
        val pnl = if (sellStocks.isEmpty()) emptyMap() else RealizedPnlCalculator.compute(linesFor(userId, sellStocks, null))

        val byRef = originTrades.groupBy { it.ref }.map { (ref, ts) ->
            val sells = ts.filter { it.side == "SELL" }.mapNotNull { pnl[it.id] }
            OriginRefPnl(ref, sells.fold(BigDecimal.ZERO) { a, r -> a + r.pnl }, sells.size, ts.size)
        }.sortedWith(compareBy(nullsLast()) { it.originRef })
        return OriginPnlResponse(
            origin = origin.name,
            totalRealizedPnl = byRef.fold(BigDecimal.ZERO) { a, r -> a + r.realizedPnl },
            sellCount = byRef.sumOf { it.sellCount },
            tradeCount = originTrades.size,
            byRef = byRef,
        )
    }

    /**
     * 주어진 매도 체결들의 실현 손익. 사용자 소유가 아닌 id·매수 체결·손익을 정할 수 없는 매도는 결과에 없다.
     * 리플레이처럼 하루치 매도만 볼 때도 평균단가는 그 종목의 처음 체결부터 쌓아야 하므로, 해당 종목의 그 시각까지
     * 체결을 한 번에 읽는다(종목 수와 무관하게 쿼리 2번).
     */
    fun forSells(userId: Long, sellTradeIds: Collection<Long>): Map<Long, RealizedPnl> {
        if (sellTradeIds.isEmpty()) return emptyMap()
        val ids = sellTradeIds.distinct()
        val sells = jdbc.query(
            "SELECT id, stock_id, traded_at FROM paper_trades WHERE user_id = ? AND side = 'SELL' AND id IN (${ids.joinToString(",") { "?" }})",
            { rs, _ -> Triple(rs.getLong("id"), rs.getLong("stock_id"), rs.getTimestamp("traded_at").toInstant()) },
            userId, *ids.toTypedArray(),
        )
        if (sells.isEmpty()) return emptyMap()
        val until = sells.maxOf { it.third }
        val all = RealizedPnlCalculator.compute(linesFor(userId, sells.map { it.second }.distinct(), until))
        return sells.mapNotNull { (id, _, _) -> all[id]?.let { id to it } }.toMap()
    }

    private data class OriginTrade(val id: Long, val stockId: Long, val side: String, val ref: Long?)

    private fun linesFor(userId: Long, stockIds: List<Long>, until: Instant?): List<PnlTradeLine> {
        val untilSql = if (until != null) " AND traded_at <= ?" else ""
        val args = mutableListOf<Any>(userId).apply { addAll(stockIds); until?.let { add(java.sql.Timestamp.from(it)) } }
        return jdbc.query(
            """SELECT id, stock_id, side, quantity, amount FROM paper_trades
               WHERE user_id = ? AND stock_id IN (${stockIds.joinToString(",") { "?" }})$untilSql
               ORDER BY traded_at, id""",
            { rs, _ -> PnlTradeLine(rs.getLong("id"), rs.getLong("stock_id"), rs.getString("side"), rs.getInt("quantity"), rs.getBigDecimal("amount")) },
            *args.toTypedArray(),
        )
    }
}
