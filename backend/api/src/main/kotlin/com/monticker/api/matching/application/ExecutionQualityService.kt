package com.monticker.api.matching.application

import com.monticker.api.common.time.KstPeriod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** 집계 입력 — 체결 1건과 그 주문의 접수 시점 기록. */
data class ExecutionFillRow(
    val orderId: Long,
    val side: String,
    val orderType: String,
    val quantity: Int,
    val fillPrice: BigDecimal,
    val filledAt: Instant,
    val quoteBid: BigDecimal?,
    val quoteAsk: BigDecimal?,
    val submittedAt: Instant?,
)

data class SlippageStat(
    /** 수량 가중 평균 슬리피지(bps). 양수 = 불리(매수는 더 비싸게, 매도는 더 싸게), 음수 = 유리. 대상이 없으면 null. */
    val avgBps: Double?,
    /** 집계에 들어간 체결 수 */
    val fillCount: Int,
)

data class LatencyStat(
    /** 시장가 주문의 접수 → 첫 체결 시간(ms). 대상이 없으면 null. */
    val avgMs: Double?,
    val p50Ms: Double?,
    val p95Ms: Double?,
    /** 집계에 들어간 주문 수 */
    val orderCount: Int,
    /** 접수 시각 기록이 없어(V88 이전) 뺀 시장가 주문 수 */
    val excludedNoSubmitTime: Int,
)

data class ExecutionQualityResponse(
    val from: LocalDate,
    val to: LocalDate,
    val slippage: SlippageStat,
    /** 방향·주문 유형별(키: BUY·SELL·MARKET·LIMIT) */
    val slippageBySide: Map<String, SlippageStat>,
    val slippageByOrderType: Map<String, SlippageStat>,
    /** 그 방향 접수 시점 최우선 호가가 없어(V88 이전 주문·실시간 호가 없음) 뺀 체결 수 */
    val excludedNoQuote: Int,
    val latency: LatencyStat,
)

/**
 * ADR-091 — 모의 체결 품질(슬리피지·엔진 지연) 계산. 순수 함수라 단위 테스트로 부호·경계를 고정한다.
 */
object ExecutionQuality {
    private const val BPS = 10_000

    /**
     * 체결 1건의 슬리피지(bps). 기준은 접수 시점의 **반대편** 최우선 호가 — 매수는 매도호가(ask), 매도는 매수호가(bid).
     *  - BUY : (체결가 − ask) ÷ ask × 10,000
     *  - SELL: (bid − 체결가) ÷ bid × 10,000
     * 양수 = 불리, 음수 = 유리(가격 개선). 그 방향 호가가 없으면 null.
     */
    fun slippageBps(side: String, fillPrice: BigDecimal, bid: BigDecimal?, ask: BigDecimal?): Double? = when (side) {
        "BUY" -> ask?.takeIf { it.signum() > 0 }?.let { (fillPrice - it).divide(it, MathContext.DECIMAL64).toDouble() * BPS }
        "SELL" -> bid?.takeIf { it.signum() > 0 }?.let { (it - fillPrice).divide(it, MathContext.DECIMAL64).toDouble() * BPS }
        else -> null
    }

    fun aggregate(from: LocalDate, to: LocalDate, rows: List<ExecutionFillRow>): ExecutionQualityResponse {
        val withSlip = rows.map { it to slippageBps(it.side, it.fillPrice, it.quoteBid, it.quoteAsk) }
        val measured = withSlip.filter { it.second != null }

        fun stat(xs: List<Pair<ExecutionFillRow, Double?>>): SlippageStat {
            val qty = xs.sumOf { it.first.quantity.toLong() }
            val avg = if (qty == 0L) null else xs.sumOf { it.first.quantity * it.second!! } / qty
            return SlippageStat(avg, xs.size)
        }

        // 지연: 시장가 주문만(지정가는 대기 시간이 섞인다). 주문당 첫 체결 시각.
        val marketOrders = rows.filter { it.orderType == "MARKET" }.groupBy { it.orderId }
        val noSubmit = marketOrders.count { (_, fs) -> fs.first().submittedAt == null }
        val latencies = marketOrders.values.mapNotNull { fs ->
            val submitted = fs.first().submittedAt ?: return@mapNotNull null
            val first = fs.minOf { it.filledAt }
            Duration.between(submitted, first).toNanos() / 1_000_000.0
        }.sorted()

        return ExecutionQualityResponse(
            from = from,
            to = to,
            slippage = stat(measured),
            slippageBySide = measured.groupBy { it.first.side }.mapValues { stat(it.value) },
            slippageByOrderType = measured.groupBy { it.first.orderType }.mapValues { stat(it.value) },
            excludedNoQuote = withSlip.size - measured.size,
            latency = LatencyStat(
                avgMs = latencies.takeIf { it.isNotEmpty() }?.average(),
                p50Ms = percentile(latencies, 0.50),
                p95Ms = percentile(latencies, 0.95),
                orderCount = latencies.size,
                excludedNoSubmitTime = noSubmit,
            ),
        )
    }

    /** 최근접 순위(nearest-rank) 백분위. 정렬된 입력. */
    fun percentile(sorted: List<Double>, p: Double): Double? {
        if (sorted.isEmpty()) return null
        val rank = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}

/**
 * ADR-091 — `/matching` 상단 "평균 슬리피지·지연". 내 체결만(user scope), 체결 시각 기준 KST 기간.
 */
@Service
@Transactional(readOnly = true)
class ExecutionQualityService(private val jdbc: JdbcTemplate) {

    companion object {
        const val FILLS_SQL = """
            SELECT f.order_id, f.side, o.order_type, f.quantity, f.fill_price, f.filled_at,
                   o.quote_bid, o.quote_ask, o.submitted_at
            FROM fills f
            JOIN orders o ON o.id = f.order_id AND o.user_id = f.user_id
            WHERE f.user_id = ? AND f.filled_at >= ? AND f.filled_at < ?
        """
    }

    fun summary(userId: Long, period: KstPeriod): ExecutionQualityResponse {
        val rows = jdbc.query(
            FILLS_SQL.trimIndent(),
            { rs, _ ->
                ExecutionFillRow(
                    orderId = rs.getLong("order_id"),
                    side = rs.getString("side"),
                    orderType = rs.getString("order_type"),
                    quantity = rs.getInt("quantity"),
                    fillPrice = rs.getBigDecimal("fill_price"),
                    filledAt = rs.getTimestamp("filled_at").toInstant(),
                    quoteBid = rs.getBigDecimal("quote_bid"),
                    quoteAsk = rs.getBigDecimal("quote_ask"),
                    submittedAt = rs.getTimestamp("submitted_at")?.toInstant(),
                )
            },
            userId, java.sql.Timestamp.from(period.start), java.sql.Timestamp.from(period.endExclusive),
        )
        return ExecutionQuality.aggregate(period.from, period.to, rows)
    }
}
