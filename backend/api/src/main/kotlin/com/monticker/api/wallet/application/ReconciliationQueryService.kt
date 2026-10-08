package com.monticker.api.wallet.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class ReconciliationDay(
    val asOfDate: LocalDate,
    val accountCash: BigDecimal,
    val reservedCash: BigDecimal,
    val ledgerSum: BigDecimal,
    /** (accountCash + reservedCash) − (초기 지급 + ledgerSum). 0이어야 한다. */
    val drift: BigDecimal,
    val mismatch: Boolean,
    val checkedAt: Instant,
)

data class ReconciliationSummary(
    /** 최근 [windowDays]일 중 불일치로 판정된 날 수 — 화면의 "잔액 불일치 N건" */
    val mismatchCount: Int,
    val windowDays: Int,
    /** 가장 최근 대사 결과(대사한 적이 없으면 null) */
    val latest: ReconciliationDay?,
    /** 불일치 날들(최신순, 최대 [windowDays]) */
    val mismatches: List<ReconciliationDay>,
)

/**
 * ADR-043 일일 대사 결과를 사용자에게 보여 준다 — `GET /api/wallet/reconciliation`.
 *
 * **스냅샷(ledger_snapshots)만 읽는다. 여기서 대사를 새로 돌리지 않는다.** 원장 FILL은 체결 커밋 뒤 비동기 리스너가
 * 쓰므로(PaperTradeEventListener) 거래 직후 실시간으로 계산하면 원장이 아직 따라오지 않은 순간을 "불일치"로 보여
 * 사용자를 놀라게 한다. 일일 배치가 그날을 마감한 뒤의 판정이 사람에게 보여 줄 값이다. 대사 자체는 쓰기(스냅샷 upsert)
 * 이기도 해서 GET이 부수효과를 가져서도 안 된다.
 */
@Service
class ReconciliationQueryService(private val jdbc: JdbcTemplate) {

    @Transactional(readOnly = true)
    fun summary(userId: Long, windowDays: Int = 90): ReconciliationSummary {
        val days = windowDays.coerceIn(1, 365)
        // ADR-089 — 스냅샷엔 초기 지급이 없다. 시작 자금은 계좌 생성 뒤 바뀌지 않으므로(updatable=false) 지금 값으로 다시 계산한다.
        val initialCapital = jdbc.query(
            "SELECT initial_capital FROM paper_accounts WHERE user_id = ?", { rs, _ -> rs.getBigDecimal("initial_capital") }, userId,
        ).firstOrNull() ?: LedgerReconciliationService.INITIAL_BALANCE
        val since = LedgerReconciliationService.ZONE.let { LocalDate.now(it).minusDays(days.toLong() - 1) }
        val mapper = { rs: java.sql.ResultSet, _: Int ->
            val cash = rs.getBigDecimal("account_cash")
            val reserved = rs.getBigDecimal("reserved_cash")
            val ledger = rs.getBigDecimal("ledger_sum")
            ReconciliationDay(
                asOfDate = rs.getDate("as_of_date").toLocalDate(),
                accountCash = cash, reservedCash = reserved, ledgerSum = ledger,
                drift = (cash + reserved) - (initialCapital + ledger),
                mismatch = rs.getBoolean("mismatch"),
                checkedAt = rs.getTimestamp("created_at").toInstant(),
            )
        }
        val latest = jdbc.query(
            "SELECT * FROM ledger_snapshots WHERE user_id = ? ORDER BY as_of_date DESC LIMIT 1", mapper, userId,
        ).firstOrNull()
        val mismatches = jdbc.query(
            "SELECT * FROM ledger_snapshots WHERE user_id = ? AND mismatch AND as_of_date >= ? ORDER BY as_of_date DESC LIMIT ?",
            mapper, userId, java.sql.Date.valueOf(since), days,
        )
        return ReconciliationSummary(mismatches.size, days, latest, mismatches)
    }
}
