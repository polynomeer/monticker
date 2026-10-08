package com.monticker.api.wallet.application

import com.monticker.api.common.time.KstPeriod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 하루 수익률 상태.
 * - OK: 계산함
 * - NO_ACCOUNT: 그날이 끝나기 전에 모의 계좌가 없었다
 * - RESET: 그날 시작 이후 계좌를 초기화했다 — 초기화는 체결 기록을 지우므로 그 이전 보유를 되살릴 수 없다
 * - NO_PRICE: 그날 시작이나 끝에 들고 있던 종목의 시세(1분봉)가 없다
 * - NO_EQUITY: 시작 평가자산이 0 이하(나눌 수 없다)
 */
enum class DailyReturnStatus { OK, NO_ACCOUNT, RESET, NO_PRICE, NO_EQUITY }

data class DailyReturn(
    val date: LocalDate,
    /** 그날 00:00 KST 평가자산(현금 + 예약금 + 보유 평가액). 알 수 없으면 null */
    val startEquity: BigDecimal?,
    /** 다음날 00:00 KST(오늘이면 지금) 평가자산 */
    val endEquity: BigDecimal?,
    /** 그날 입출금(DEPOSIT·WITHDRAWAL) 합 — 손익에서 뺀다 */
    val netFlow: BigDecimal,
    /** 끝 − 시작 − 입출금 */
    val pnl: BigDecimal?,
    /** pnl ÷ 시작 평가자산 × 100 */
    val returnPct: Double?,
    val status: DailyReturnStatus,
)

data class DailyReturnsResponse(val from: LocalDate, val to: LocalDate, val days: List<DailyReturn>)

/**
 * ADR-091 일별 수익률 = 그날 손익 ÷ 그날 시작 평가자산. 순수 함수 — 경계·상태 판정을 단위 테스트로 고정한다.
 *
 * [points]는 날짜 경계 시각이다: `points[i]` = days[i] 00:00 KST, `points[i+1]` = 다음 경계(마지막이 오늘이면 지금).
 * [equity]는 각 경계의 평가자산(시세가 없으면 null), [flows]는 날짜별 입출금 합.
 */
object DailyReturns {
    fun compute(
        days: List<LocalDate>,
        points: List<Instant>,
        equity: List<BigDecimal?>,
        flows: List<BigDecimal>,
        accountCreatedAt: Instant?,
        lastResetAt: Instant?,
    ): List<DailyReturn> {
        require(points.size == days.size + 1 && equity.size == points.size && flows.size == days.size)
        return days.mapIndexed { i, d ->
            val s = points[i]
            val e = points[i + 1]
            val e0 = equity[i]
            val e1 = equity[i + 1]
            val flow = flows[i]
            val status = when {
                accountCreatedAt == null || !e.isAfter(accountCreatedAt) -> DailyReturnStatus.NO_ACCOUNT
                lastResetAt != null && s.isBefore(lastResetAt) -> DailyReturnStatus.RESET
                e0 == null || e1 == null -> DailyReturnStatus.NO_PRICE
                e0.signum() <= 0 -> DailyReturnStatus.NO_EQUITY
                else -> DailyReturnStatus.OK
            }
            if (status != DailyReturnStatus.OK) {
                val known = status == DailyReturnStatus.NO_PRICE || status == DailyReturnStatus.NO_EQUITY
                DailyReturn(d, e0.takeIf { known }, e1.takeIf { known }, flow, null, null, status)
            } else {
                val pnl = e1!! - e0!! - flow
                val pct = pnl.divide(e0, 14, RoundingMode.HALF_UP).multiply(BigDecimal(100)).toDouble()
                DailyReturn(d, e0, e1, flow, pnl, pct, status)
            }
        }
    }
}

/**
 * ADR-091 — /wallet/replay 날짜별 수익률. 평가자산은 /wallet 총자산과 같은 정의다: 현금 + 예약금 + 보유 평가액.
 *  - 현금 + 예약금(경계 시점) = 초기 지급 + 그 시각 이전 현금 영향 원장 합 — ADR-043 대사 불변식 그대로
 *    ([LedgerReconciliationService.CASH_EVENT_TYPES]). 지금 시점이면 /wallet의 cash + reserved와 같다(대사 불일치가 없다면).
 *  - 보유 평가액 = 그 시각 이전 체결로 쌓은 종목별 수량 × 그 시각 직전 1분봉 종가.
 *
 * 쿼리 수는 날짜 수·종목 수와 무관하다(계좌 1, 원장 2, 체결 2, 종가 1 — 종가는 (종목, 경계) 쌍을 배열로 넘겨 LATERAL 한 번).
 * KST 경계는 Kotlin에서 계산해 바인딩한다.
 */
@Service
@Transactional(readOnly = true)
class DailyReturnService(
    private val jdbc: JdbcTemplate,
    /** 테스트가 "오늘"을 고정한다. 운영은 기본값(Clock 빈 없음). */
    private val clock: Clock = Clock.systemUTC(),
) {

    private val cashTypes = LedgerReconciliationService.CASH_EVENT_TYPES
    private val cashTypeSql = cashTypes.joinToString(",") { "'$it'" }

    fun daily(userId: Long, period: KstPeriod): DailyReturnsResponse {
        // 밀리초로 자른다 — 종가 쿼리가 경계를 epoch ms로 주고받는다
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val today = KstPeriod.today(now)
        val days = period.days.filter { !it.isAfter(today) }
        if (days.isEmpty()) return DailyReturnsResponse(period.from, period.to, emptyList())
        val points = days.map { KstPeriod.startOf(it) } + listOf(
            if (days.last() == today) now else KstPeriod.startOf(days.last().plusDays(1)),
        )
        val first = points.first()
        val last = points.last()

        val account = jdbc.query(
            "SELECT initial_capital, created_at FROM paper_accounts WHERE user_id = ?",
            { rs, _ -> rs.getBigDecimal("initial_capital") to rs.getTimestamp("created_at").toInstant() }, userId,
        ).firstOrNull()
        if (account == null) {
            return DailyReturnsResponse(period.from, period.to, DailyReturns.compute(
                days, points, points.map { null }, days.map { BigDecimal.ZERO }, null, null,
            ))
        }

        val lastResetAt = jdbc.query(
            "SELECT MAX(created_at) AS t FROM ledger_events WHERE user_id = ? AND dedup_key LIKE 'RESET:%'",
            { rs, _ -> rs.getTimestamp("t")?.toInstant() }, userId,
        ).firstOrNull()

        // 현금 + 예약금
        val cashBefore = jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount), 0) FROM ledger_events WHERE user_id = ? AND event_type IN ($cashTypeSql) AND created_at < ?",
            BigDecimal::class.java, userId, Timestamp.from(first),
        ) ?: BigDecimal.ZERO
        data class Cash(val at: Instant, val amount: BigDecimal, val type: String)
        val cashEvents = jdbc.query(
            """SELECT created_at, amount, event_type FROM ledger_events
               WHERE user_id = ? AND event_type IN ($cashTypeSql) AND created_at >= ? AND created_at < ?
               ORDER BY created_at, id""",
            { rs, _ -> Cash(rs.getTimestamp("created_at").toInstant(), rs.getBigDecimal("amount"), rs.getString("event_type")) },
            userId, Timestamp.from(first), Timestamp.from(last),
        )
        val cashAt = points.map { p ->
            account.first + cashBefore + cashEvents.filter { it.at.isBefore(p) }.fold(BigDecimal.ZERO) { a, c -> a + c.amount }
        }
        val flows = days.indices.map { i ->
            cashEvents.filter { (it.type == "DEPOSIT" || it.type == "WITHDRAWAL") && !it.at.isBefore(points[i]) && it.at.isBefore(points[i + 1]) }
                .fold(BigDecimal.ZERO) { a, c -> a + c.amount }
        }

        // 보유 수량
        val qtyBefore = jdbc.query(
            """SELECT stock_id, SUM(CASE WHEN side = 'BUY' THEN quantity ELSE -quantity END) AS q
               FROM paper_trades WHERE user_id = ? AND traded_at < ? GROUP BY stock_id""",
            { rs, _ -> rs.getLong("stock_id") to rs.getLong("q") }, userId, Timestamp.from(first),
        ).toMap()
        data class T(val at: Instant, val stockId: Long, val delta: Long)
        val trades = jdbc.query(
            """SELECT traded_at, stock_id, CASE WHEN side = 'BUY' THEN quantity ELSE -quantity END AS d
               FROM paper_trades WHERE user_id = ? AND traded_at >= ? AND traded_at < ? ORDER BY traded_at, id""",
            { rs, _ -> T(rs.getTimestamp("traded_at").toInstant(), rs.getLong("stock_id"), rs.getLong("d")) },
            userId, Timestamp.from(first), Timestamp.from(last),
        )
        val holdingsAt: List<Map<Long, Long>> = points.map { p ->
            val m = HashMap(qtyBefore)
            trades.filter { it.at.isBefore(p) }.forEach { m.merge(it.stockId, it.delta, Long::plus) }
            m.filterValues { it > 0 }
        }

        val pairs = points.indices.flatMap { i -> holdingsAt[i].keys.map { it to points[i] } }.distinct()
        val closes = closesAt(pairs)
        val equity = points.indices.map { i ->
            var total = cashAt[i]
            for ((stockId, q) in holdingsAt[i]) {
                val c = closes[stockId to points[i]] ?: return@map null
                total += c.multiply(BigDecimal(q))
            }
            total
        }

        return DailyReturnsResponse(period.from, period.to, DailyReturns.compute(days, points, equity, flows, account.second, lastResetAt))
    }

    /** (종목, 시각) 쌍마다 그 시각 직전 1분봉 종가. 한 쿼리 — 배열 unnest + LATERAL(idx_candles_1m_stock_time). */
    private fun closesAt(pairs: List<Pair<Long, Instant>>): Map<Pair<Long, Instant>, BigDecimal> {
        if (pairs.isEmpty()) return emptyMap()
        val rows = jdbc.query(
            """SELECT p.stock_id, p.ms, c.close
               FROM unnest(?::bigint[], ?::bigint[]) AS p(stock_id, ms)
               JOIN LATERAL (
                   SELECT close FROM candles_1m
                   WHERE stock_id = p.stock_id AND candle_time < to_timestamp(p.ms / 1000.0)
                   ORDER BY candle_time DESC LIMIT 1
               ) c ON TRUE""",
            PreparedStatementSetter { ps ->
                ps.setArray(1, ps.connection.createArrayOf("bigint", pairs.map { it.first }.toTypedArray()))
                ps.setArray(2, ps.connection.createArrayOf("bigint", pairs.map { it.second.toEpochMilli() }.toTypedArray()))
            },
            { rs, _ -> (rs.getLong("stock_id") to Instant.ofEpochMilli(rs.getLong("ms"))) to rs.getBigDecimal("close") },
        )
        return rows.toMap()
    }
}
