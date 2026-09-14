package com.monticker.api.wallet

import com.monticker.api.support.PostgresIntegrationTest
import com.monticker.api.wallet.application.LedgerReconciliationService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDate

/**
 * ADR-043 §2·§3 — 대사 불변식 `cash + reserved = 초기 지급 + Σ원장[현금 타입]`을 실제 Postgres에서 검증한다.
 * 서비스는 순수 SQL이라 mock으로는 검증할 게 없다(테스트가 SQL 문자열을 따라 쓰게 된다).
 */
class LedgerReconciliationIntegrationTest : PostgresIntegrationTest() {

    private val registry = SimpleMeterRegistry()
    private val service = LedgerReconciliationService(jdbcTemplate, registry, "alert")
    private val today: LocalDate = LocalDate.now(LedgerReconciliationService.ZONE)

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "recon-${System.nanoTime()}@test.local", "recon",
    )!!

    private fun account(userId: Long, cash: String) =
        jdbcTemplate.update("INSERT INTO paper_accounts (user_id, cash) VALUES (?, ?)", userId, BigDecimal(cash))

    private fun ledger(userId: Long, type: String, amount: String, paperTradeId: Long? = null): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO ledger_events (user_id, event_type, amount, paper_trade_id) VALUES (?, ?, ?, ?) RETURNING id",
            Long::class.java, userId, type, BigDecimal(amount), paperTradeId,
        )!!

    private fun stockId(): Long = jdbcTemplate.queryForObject("SELECT id FROM stocks ORDER BY id LIMIT 1", Long::class.java)!!

    private fun openBuyOrder(userId: Long, qty: Int, limit: String, filled: Int = 0, status: String = "PENDING"): Long =
        jdbcTemplate.queryForObject(
            """INSERT INTO orders (user_id, stock_id, side, order_type, quantity, limit_price, filled_qty, status)
               VALUES (?, ?, 'BUY', 'LIMIT', ?, ?, ?, ?) RETURNING id""",
            Long::class.java, userId, stockId(), qty, BigDecimal(limit), filled, status,
        )!!

    private fun mismatches() = registry.find("ledger_reconciliation_mismatch_total").counter()?.count() ?: 0.0

    @Test
    fun `a user with no account and no ledger reconciles to zero drift`() {
        val userId = newUser()

        val r = service.reconcile(userId, today)

        assertThat(r.drift).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(r.mismatch).isFalse()
        assertThat(r.accountCash).isEqualByComparingTo(LedgerReconciliationService.INITIAL_BALANCE)
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ledger_snapshots WHERE user_id = ? AND as_of_date = ?", Long::class.java, userId, today,
        )).isEqualTo(1L)
    }

    @Test
    fun `fills, non-cash events, open reservations and cancel refunds all satisfy the invariant`() {
        val userId = newUser()
        // 초기 1,000만 → 매수 100만 체결(FILL −1,000,000) → 매도 30만(SETTLEMENT +300,000)
        // → 정산 수수료 150원(PAPER_SETTLEMENT_COMPLETE −150) → cash = 9,299,850
        ledger(userId, "FILL", "-1000000", paperTradeId = 999_999L)   // fills.id — V43에서 FK가 풀렸으므로 paper_trades 행 없이 저장된다
        ledger(userId, "SETTLEMENT", "300000")
        ledger(userId, "PAPER_SETTLEMENT_COMPLETE", "-150")
        // 모의투자 현금과 무관한 원장: 구독 결제·크리에이터 정산·증권사 정산 — 합계에서 제외돼야 한다
        ledger(userId, "SUBSCRIPTION_PAYMENT", "-9900")
        ledger(userId, "CREATOR_EARNING_CREDITED", "12345")
        ledger(userId, "BROKERAGE_SETTLEMENT", "-777777")
        // 미체결 BUY LIMIT 100주 × 2,000 = 200,000 예약 → cash에서 미리 빠짐, 원장엔 없음
        openBuyOrder(userId, qty = 100, limit = "2000")
        // 부분 체결 주문: 잔량 40주 × 1,500 = 60,000 만 예약 상태
        openBuyOrder(userId, qty = 100, limit = "1500", filled = 60, status = "PARTIALLY_FILLED")
        // 취소된 주문: 예약 해제 — 원장엔 CASH_UNRESERVED(합계 제외), cash는 돌아왔고 주문은 CANCELLED라 예약 항에서 빠짐
        openBuyOrder(userId, qty = 10, limit = "5000", status = "CANCELLED")
        ledger(userId, "CASH_UNRESERVED", "50000")
        account(userId, "9039850")   // 9,299,850 − 200,000 − 60,000

        val r = service.reconcile(userId, today)

        assertThat(r.ledgerSum).isEqualByComparingTo(BigDecimal("-700150"))
        assertThat(r.reservedCash).isEqualByComparingTo(BigDecimal("260000"))
        assertThat(r.drift).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(r.mismatch).isFalse()
        assertThat(mismatches()).isEqualTo(0.0)
    }

    @Test
    fun `a cash change with no ledger row is reported as a mismatch and never corrected`() {
        val userId = newUser()
        ledger(userId, "FILL", "-500000")
        account(userId, "9500001")   // 1원이 어디선가 생겼다

        val r = service.reconcile(userId, today)

        assertThat(r.drift).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(r.mismatch).isTrue()
        assertThat(mismatches()).isEqualTo(1.0)
        assertThat(registry.find("ledger_reconciliation_mismatch_total").tag("mode", "alert").counter()).isNotNull()
        // 자동 교정 금지 — 잔고도 원장도 그대로다
        assertThat(jdbcTemplate.queryForObject("SELECT cash FROM paper_accounts WHERE user_id = ?", BigDecimal::class.java, userId))
            .isEqualByComparingTo(BigDecimal("9500001"))
        assertThat(jdbcTemplate.queryForObject("SELECT mismatch FROM ledger_snapshots WHERE user_id = ? AND as_of_date = ?", Boolean::class.java, userId, today))
            .isTrue()
    }

    @Test
    fun `reconciliation continues from the previous snapshot instead of re-summing the whole ledger`() {
        val userId = newUser()
        val yesterday = today.minusDays(1)
        val e1 = ledger(userId, "FILL", "-100000")
        account(userId, "9900000")
        service.reconcile(userId, yesterday)

        // 어제 스냅샷의 합을 일부러 틀리게 고친다 — 오늘 대사가 전체를 다시 합산하면 이 값이 무시되고,
        // 스냅샷 델타 방식이면 (틀린 합 + 오늘 델타)가 나와야 한다.
        jdbcTemplate.update("UPDATE ledger_snapshots SET ledger_sum = ? WHERE user_id = ? AND as_of_date = ?", BigDecimal("-1"), userId, yesterday)
        val e2 = ledger(userId, "SETTLEMENT", "40000")
        jdbcTemplate.update("UPDATE paper_accounts SET cash = cash + 40000 WHERE user_id = ?", userId)

        val r = service.reconcile(userId, today)

        assertThat(r.ledgerSum).isEqualByComparingTo(BigDecimal("39999"))   // −1 + 40,000 → 델타 경로를 탔다
        assertThat(r.lastEventId).isEqualTo(e2)
        assertThat(e2).isGreaterThan(e1)
        // 같은 날 재실행은 그날 스냅샷을 덮어쓴다 (PK 충돌 없음)
        service.reconcile(userId, today)
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ledger_snapshots WHERE user_id = ?", Long::class.java, userId,
        )).isEqualTo(2L)
    }

    @Test
    fun `the batch reader picks only users with ledger activity on that day`() {
        val active = newUser(); val idle = newUser(); val stale = newUser()
        ledger(active, "FILL", "-1")
        val old = ledger(stale, "FILL", "-1")
        jdbcTemplate.update("UPDATE ledger_events SET created_at = now() - interval '3 days' WHERE id = ?", old)
        val (from, to) = service.dayRange(today)

        val users = jdbcTemplate.queryForList(
            LedgerReconciliationService.ACTIVE_USERS_SQL.trimIndent(), Long::class.java, Timestamp.from(from), Timestamp.from(to),
        )

        assertThat(users).contains(active).doesNotContain(idle, stale)
    }
}
