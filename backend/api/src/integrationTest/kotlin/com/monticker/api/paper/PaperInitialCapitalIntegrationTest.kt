package com.monticker.api.paper

import com.monticker.api.auth.application.InterestSector
import com.monticker.api.auth.application.UsageStyle
import com.monticker.api.auth.application.UserPreferenceService
import com.monticker.api.common.exception.BusinessRuleException
import com.monticker.api.paper.application.PaperAccountService
import com.monticker.api.support.PostgresIntegrationTest
import com.monticker.api.wallet.application.LedgerReconciliationService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.LocalDate

/**
 * ADR-089 / V86 — 시작 자금 컬럼·CHECK, 첫 생성만 허용, 대사 불변식이 계좌별 시작 자금을 쓰는지,
 * user_preferences 배열 저장·CHECK를 실제 Postgres에서 검증한다.
 */
class PaperInitialCapitalIntegrationTest : PostgresIntegrationTest() {

    private val accounts = PaperAccountService(jdbcTemplate)
    private val preferences = UserPreferenceService(jdbcTemplate)

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "capital-${System.nanoTime()}@test.local", "capital",
    )!!

    private fun capitalOf(userId: Long): BigDecimal =
        jdbcTemplate.queryForObject("SELECT initial_capital FROM paper_accounts WHERE user_id = ?", BigDecimal::class.java, userId)!!

    @Test
    fun `rows created the old way default to 10,000,000`() {
        val userId = newUser()
        // OrderSagaOrchestrator의 지연 생성 SQL과 같은 모양 — initial_capital을 모른다
        jdbcTemplate.update(
            "INSERT INTO paper_accounts (user_id, cash, created_at, updated_at) VALUES (?, 10000000, now(), now()) ON CONFLICT (user_id) DO NOTHING",
            userId,
        )
        assertThat(capitalOf(userId)).isEqualByComparingTo("10000000")
    }

    @Test
    fun `the check constraint rejects a non-whitelisted capital written directly`() {
        val userId = newUser()
        assertThatThrownBy {
            jdbcTemplate.update("INSERT INTO paper_accounts (user_id, cash, initial_capital) VALUES (?, 50000000, 50000000)", userId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `open creates once, is idempotent for the same value and refuses a different value without touching cash`() {
        val userId = newUser()

        val first = accounts.open(userId, BigDecimal("30000000"))
        assertThat(first.created).isTrue()
        assertThat(first.cash).isEqualByComparingTo("30000000")

        jdbcTemplate.update("UPDATE paper_accounts SET cash = 12345 WHERE user_id = ?", userId)   // 거래로 줄었다고 치자

        val again = accounts.open(userId, BigDecimal("30000000"))
        assertThat(again.created).isFalse()
        assertThat(again.cash).isEqualByComparingTo("12345")

        assertThatThrownBy { accounts.open(userId, BigDecimal("100000000")) }.isInstanceOf(BusinessRuleException::class.java)
        assertThat(capitalOf(userId)).isEqualByComparingTo("30000000")
        assertThat(jdbcTemplate.queryForObject("SELECT cash FROM paper_accounts WHERE user_id = ?", BigDecimal::class.java, userId))
            .isEqualByComparingTo("12345")
    }

    @Test
    fun `reconciliation uses the account's initial capital`() {
        val userId = newUser()
        accounts.open(userId, BigDecimal("100000000"))
        jdbcTemplate.update("INSERT INTO ledger_events (user_id, event_type, amount) VALUES (?, 'FILL', -2500000)", userId)
        jdbcTemplate.update("UPDATE paper_accounts SET cash = 97500000 WHERE user_id = ?", userId)

        val service = LedgerReconciliationService(jdbcTemplate, SimpleMeterRegistry(), "alert")
        val r = service.reconcile(userId, LocalDate.now(LedgerReconciliationService.ZONE))

        assertThat(r.drift).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(r.mismatch).isFalse()
    }

    @Test
    fun `preferences round-trip through the varchar array and are replaced on save`() {
        val userId = newUser()
        assertThat(preferences.get(userId).updatedAt).isNull()

        preferences.save(userId, listOf(InterestSector.SEMICONDUCTOR, InterestSector.ETF), UsageStyle.QUANT)
        val saved = preferences.get(userId)
        assertThat(saved.interestSectors).containsExactly(InterestSector.SEMICONDUCTOR, InterestSector.ETF)
        assertThat(saved.usageStyle).isEqualTo(UsageStyle.QUANT)
        assertThat(saved.updatedAt).isNotNull()

        preferences.save(userId, emptyList(), null)
        val cleared = preferences.get(userId)
        assertThat(cleared.interestSectors).isEmpty()
        assertThat(cleared.usageStyle).isNull()

        // 다른 사용자의 행은 보이지 않는다
        assertThat(preferences.get(newUser()).interestSectors).isEmpty()
    }

    @Test
    fun `preference check constraints reject values outside the whitelist`() {
        val userId = newUser()
        assertThatThrownBy {
            jdbcTemplate.update("INSERT INTO user_preferences (user_id, interest_sectors) VALUES (?, ARRAY['CRYPTO']::varchar[])", userId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update("INSERT INTO user_preferences (user_id, usage_style) VALUES (?, 'YOLO')", userId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
