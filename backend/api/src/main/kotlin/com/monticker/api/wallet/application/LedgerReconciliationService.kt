package com.monticker.api.wallet.application

import com.monticker.api.common.domain.Money
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

@NamedInterface("api")
data class ReconciliationResult(
    val userId: Long,
    val asOfDate: LocalDate,
    val ledgerSum: BigDecimal,
    val accountCash: BigDecimal,
    val reservedCash: BigDecimal,
    val lastEventId: Long,
    /** (accountCash + reservedCash) − (초기 지급 + ledgerSum). 0이어야 한다. */
    val drift: BigDecimal,
) {
    val mismatch: Boolean get() = drift.abs() > TOLERANCE

    companion object {
        val TOLERANCE: BigDecimal = BigDecimal("0.0001")
    }
}

/**
 * ADR-043 §2·§3 — 컬럼 잔고(paper_accounts.cash)와 append-only 원장의 일일 대사.
 *
 * 불변식: `cash + reserved = INITIAL_BALANCE + Σ ledger.amount[CASH_EVENT_TYPES]`
 *
 * - INITIAL_BALANCE: 계정 생성 시 지급되는 1,000만 원은 원장에 DEPOSIT으로 기록되지 않는다 — 상수로 더한다.
 * - reserved: 미체결 BUY 주문의 limit_price × 잔량. OrderSagaOrchestrator.reserveCash가 제출 시점에
 *   cash에서 미리 빼지만 원장에는 아무것도 쓰지 않는다(예약은 실현된 이동이 아니다). 그래서 잔고 쪽에 되돌려 더한다.
 * - CASH_EVENT_TYPES: 현금 컬럼에 실제로 반영되는 이벤트만. SUBSCRIPTION_PAYMENT(PG 결제)·CREATOR_*(정산 계좌)·
 *   BROKERAGE_SETTLEMENT(증권사 계좌)는 모의투자 현금과 무관하고, CASH_RESERVED/UNRESERVED는 예약금의
 *   이동이라 reserved 항에서 이미 상쇄된다.
 *
 * 스냅샷은 replay 가속용이 아니다 — 직전 스냅샷의 (ledger_sum, last_event_id)에서 델타만 더해
 * 대사 비용을 O(당일 이벤트)로 만들고, 드리프트가 생기면 "언제부터"를 날짜 단위로 좁히기 위한 것이다.
 *
 * 불일치는 자동으로 고치지 않는다. 원장 누락(기록 실패)인지 잔고 오염(잘못된 UPDATE)인지 기계가 판단할 수
 * 없고, 방향을 틀리면 사용자 돈이 사라진다. 카운터를 올리고(LedgerMismatch 알람) 사람이 조사한다.
 */
@NamedInterface("api")
@Service
class LedgerReconciliationService(
    private val jdbc: JdbcTemplate,
    registry: MeterRegistry,
    /**
     * report: 불일치를 WARN + mode="report" 카운터로만 남긴다 — ADR-043 "최초 실행은 알람 없이 리포트만".
     * alert : ERROR + mode="alert" 카운터 → LedgerMismatch 페이지 알람. 기존 드리프트를 정리한 뒤 켠다.
     */
    @Value("\${app.wallet.reconciliation.mode:alert}") private val mode: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val checked  = Counter.builder("ledger_reconciliation_checked_total")
        .description("대사한 (유저, 날짜) 건수").register(registry)
    private val mismatch = Counter.builder("ledger_reconciliation_mismatch_total")
        .description("컬럼 잔고 + 예약금 ≠ 초기 지급 + 원장 합인 (유저, 날짜) 건수. 자동 교정 금지.")
        .tag("mode", mode).register(registry)

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        val INITIAL_BALANCE: BigDecimal = Money.INITIAL_BALANCE.amount

        /** 현금 컬럼에 반영되는 원장 이벤트 타입. LedgerEventType과 함께 유지한다. */
        val CASH_EVENT_TYPES = listOf(
            "DEPOSIT", "WITHDRAWAL", "FILL", "PARTIAL_FILL", "FEE", "SETTLEMENT", "PAPER_SETTLEMENT_COMPLETE",
        )
        private val TYPE_LIST = CASH_EVENT_TYPES.joinToString(",") { "'$it'" }

        /** 배치 리더가 쓰는 대상 선정 SQL — 그날 원장 이벤트가 하나라도 있는 유저. 비용은 유저 수가 아니라 당일 거래 유저 수에 비례. */
        const val ACTIVE_USERS_SQL = """
            SELECT DISTINCT user_id FROM ledger_events
            WHERE created_at >= ? AND created_at < ?
            ORDER BY user_id
        """
    }

    fun dayRange(date: LocalDate) =
        date.atStartOfDay(ZONE).toInstant() to date.plusDays(1).atStartOfDay(ZONE).toInstant()

    /** 배치(batch 모듈)는 wallet::api만 볼 수 있다 — companion은 named interface 밖이라 인스턴스로 노출한다. */
    fun today(): LocalDate = LocalDate.now(ZONE)
    fun activeUsersSql(): String = ACTIVE_USERS_SQL.trimIndent()

    @Transactional
    fun reconcile(userId: Long, asOf: LocalDate): ReconciliationResult {
        // 직전 스냅샷(같은 날 재실행이면 그 전날) — 없으면 원장 처음부터.
        val prev = jdbc.query(
            "SELECT ledger_sum, last_event_id FROM ledger_snapshots WHERE user_id = ? AND as_of_date < ? ORDER BY as_of_date DESC LIMIT 1",
            { rs, _ -> rs.getBigDecimal("ledger_sum") to rs.getLong("last_event_id") },
            userId, asOf,
        ).firstOrNull() ?: (BigDecimal.ZERO to 0L)

        val delta = jdbc.queryForMap(
            """
            SELECT COALESCE(SUM(amount), 0) AS sum, COALESCE(MAX(id), ?) AS last_id
            FROM ledger_events
            WHERE user_id = ? AND id > ? AND event_type IN ($TYPE_LIST)
            """.trimIndent(),
            prev.second, userId, prev.second,
        )
        val ledgerSum   = prev.first + (delta["sum"] as BigDecimal)
        val lastEventId = (delta["last_id"] as Number).toLong()

        // 계정 행이 없으면 아직 아무 거래도 없는 것 — PaperAccountQueryService와 같은 해석(초기 잔고).
        val accountCash = jdbc.query(
            "SELECT cash FROM paper_accounts WHERE user_id = ?", { rs, _ -> rs.getBigDecimal("cash") }, userId,
        ).firstOrNull() ?: INITIAL_BALANCE
        val reservedCash = jdbc.queryForObject(RESERVED_CASH_SQL, BigDecimal::class.java, userId) ?: BigDecimal.ZERO

        val result = ReconciliationResult(
            userId = userId, asOfDate = asOf, ledgerSum = ledgerSum,
            accountCash = accountCash, reservedCash = reservedCash, lastEventId = lastEventId,
            drift = (accountCash + reservedCash) - (INITIAL_BALANCE + ledgerSum),
        )

        jdbc.update(
            """
            INSERT INTO ledger_snapshots (user_id, as_of_date, ledger_sum, account_cash, reserved_cash, last_event_id, mismatch)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, as_of_date) DO UPDATE SET
                ledger_sum = EXCLUDED.ledger_sum, account_cash = EXCLUDED.account_cash,
                reserved_cash = EXCLUDED.reserved_cash, last_event_id = EXCLUDED.last_event_id,
                mismatch = EXCLUDED.mismatch, created_at = now()
            """.trimIndent(),
            userId, asOf, ledgerSum, accountCash, reservedCash, lastEventId, result.mismatch,
        )

        checked.increment()
        if (result.mismatch) {
            mismatch.increment()
            val msg = "[LedgerReconciliation] 불일치 userId={} asOf={} cash={} reserved={} ledgerSum={} drift={} — 자동 교정 금지, 조사 필요"
            if (mode == "report") log.warn(msg, userId, asOf, accountCash, reservedCash, ledgerSum, result.drift)
            else                  log.error(msg, userId, asOf, accountCash, reservedCash, ledgerSum, result.drift)
        }
        return result
    }
}
