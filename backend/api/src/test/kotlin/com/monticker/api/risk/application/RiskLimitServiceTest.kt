package com.monticker.api.risk.application

import com.monticker.api.risk.domain.RiskLimit
import com.monticker.api.risk.domain.RiskLimitField
import com.monticker.api.risk.domain.RiskLimitField.CONCENTRATION_LIMIT_PCT
import com.monticker.api.risk.domain.RiskLimitField.IS_ACTIVE
import com.monticker.api.risk.domain.RiskLimitField.MAX_POSITION_COUNT
import com.monticker.api.risk.domain.RiskLimitField.SECTOR_CONCENTRATION_LIMIT_PCT
import com.monticker.api.risk.domain.RiskLimitField.VAR_LIMIT_PCT
import com.monticker.api.risk.infrastructure.RiskLimitRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/** ADR-069 — 강화 즉시·완화 24시간 뒤. pending 테이블은 메모리 가짜로 둔다. */
class RiskLimitServiceTest {

    private val userId = 3L
    private val t0 = Instant.parse("2026-10-06T00:00:00Z")
    private val stored = RiskLimit(userId = userId) // 일손실 3, 집중도 30, VaR 5, 종목 10, 시간당 5, 섹터 없음, 켬

    private data class Row(val value: BigDecimal?, val requestedAt: Instant, val effectiveAt: Instant)
    private val pending = linkedMapOf<RiskLimitField, Row>()

    private val repo = mockk<RiskLimitRepository> {
        every { findByUserId(userId) } returns Optional.of(stored)
        every { findForUpdate(userId) } returns Optional.of(stored)
        val saved = slot<RiskLimit>()
        every { save(capture(saved)) } answers { saved.captured }
    }

    private fun flat(args: List<Any?>) = args.flatMap { if (it is Array<*>) it.toList() else listOf(it) }

    private val jdbc = mockk<JdbcTemplate> {
        every { update(match<String> { it.startsWith("INSERT INTO risk_limits") }, *anyVararg()) } returns 0
        every { query(match<String> { it.contains("SELECT field, new_value FROM") }, any<RowMapper<Any>>(), *anyVararg()) } answers {
            val now = (flat(args.drop(2))[1] as Timestamp).toInstant()
            pending.filterValues { !it.effectiveAt.isAfter(now) }.map { (f, r) -> f to r.value }
        }
        every { query(match<String> { it.contains("ORDER BY effective_at") }, any<RowMapper<Any>>(), *anyVararg()) } answers {
            pending.entries.sortedBy { it.value.effectiveAt }.map { (f, r) -> PendingLimitChange(f.key, r.value, r.requestedAt, r.effectiveAt) }
        }
        every { update(match<String> { it.startsWith("DELETE") && it.contains("effective_at <= ?") }, *anyVararg()) } answers {
            val now = (flat(args.drop(1))[1] as Timestamp).toInstant()
            pending.entries.removeIf { !it.value.effectiveAt.isAfter(now) }; 1
        }
        every { update(match<String> { it.startsWith("DELETE") && it.contains("field = ?") }, *anyVararg()) } answers {
            pending.remove(RiskLimitField.valueOf(flat(args.drop(1))[1] as String)); 1
        }
        every { update(match<String> { it.startsWith("INSERT INTO risk_limit_pending_changes") }, *anyVararg()) } answers {
            val a = flat(args.drop(1))
            pending[RiskLimitField.valueOf(a[1] as String)] =
                Row(a[2] as BigDecimal?, (a[3] as Timestamp).toInstant(), (a[4] as Timestamp).toInstant()); 1
        }
    }

    private val service = RiskLimitService(repo, jdbc).apply { at(t0) }

    private fun RiskLimitService.at(t: Instant) { clock = Clock.fixed(t, ZoneOffset.UTC) }
    private fun hours(h: Long) = t0.plus(Duration.ofHours(h))

    @Test
    fun `한도를 낮추는 강화는 즉시 적용된다`() {
        val view = service.update(userId, mapOf(VAR_LIMIT_PCT to BigDecimal("3")))

        assertThat(view.limits.varLimitPct).isEqualByComparingTo("3")
        assertThat(view.pending).isEmpty()
        assertThat(service.effective(userId).varLimitPct).isEqualByComparingTo("3")
    }

    @Test
    fun `한도를 올리는 완화는 24시간 뒤에 적용된다`() {
        val view = service.update(userId, mapOf(CONCENTRATION_LIMIT_PCT to BigDecimal("50")))

        assertThat(view.limits.concentrationLimitPct).isEqualByComparingTo("30")
        assertThat(view.pending.single().effectiveAt).isEqualTo(hours(24))

        service.at(hours(23))
        assertThat(service.effective(userId).concentrationLimitPct).isEqualByComparingTo("30")

        service.at(hours(24))
        assertThat(service.effective(userId).concentrationLimitPct).isEqualByComparingTo("50")
        // 유효 한도 계산은 저장된 엔티티를 건드리지 않는다
        assertThat(stored.concentrationLimitPct).isEqualByComparingTo("30")

        // 조회가 DB에 반영(승격)한다
        val promoted = service.view(userId)
        assertThat(promoted.limits.concentrationLimitPct).isEqualByComparingTo("50")
        assertThat(promoted.pending).isEmpty()
    }

    @Test
    fun `리스크 체크 끄기는 완화, 켜기는 강화다`() {
        service.update(userId, mapOf(IS_ACTIVE to BigDecimal.ZERO))
        assertThat(service.effective(userId).isActive).isTrue()
        assertThat(pending.keys).containsExactly(IS_ACTIVE)

        // 다시 켬(현재 값과 같다) → 대기 중인 끄기를 취소한다
        service.update(userId, mapOf(IS_ACTIVE to BigDecimal.ONE))
        assertThat(pending).isEmpty()

        stored.isActive = false
        service.update(userId, mapOf(IS_ACTIVE to BigDecimal.ONE))
        assertThat(stored.isActive).isTrue()
    }

    @Test
    fun `더 느슨하게 다시 요청하면 시계가 다시 돌고, 덜 느슨하게 줄이면 기존 시각을 지킨다`() {
        service.update(userId, mapOf(MAX_POSITION_COUNT to BigDecimal(20)))

        service.at(hours(10))
        service.update(userId, mapOf(MAX_POSITION_COUNT to BigDecimal(15)))
        assertThat(pending[MAX_POSITION_COUNT]!!.effectiveAt).isEqualTo(hours(24))

        service.update(userId, mapOf(MAX_POSITION_COUNT to BigDecimal(40)))
        assertThat(pending[MAX_POSITION_COUNT]!!.effectiveAt).isEqualTo(hours(34))
    }

    @Test
    fun `완화 대기 중에 강화하면 대기 변경이 사라지고 즉시 적용된다`() {
        service.update(userId, mapOf(VAR_LIMIT_PCT to BigDecimal("8")))
        service.update(userId, mapOf(VAR_LIMIT_PCT to BigDecimal("4")))

        assertThat(pending).isEmpty()
        assertThat(stored.varLimitPct).isEqualByComparingTo("4")
    }

    @Test
    fun `섹터 한도 설정은 즉시, 해제는 24시간 뒤다`() {
        service.update(userId, mapOf(SECTOR_CONCENTRATION_LIMIT_PCT to BigDecimal("40")))
        assertThat(stored.sectorConcentrationLimitPct).isEqualByComparingTo("40")

        service.update(userId, mapOf(SECTOR_CONCENTRATION_LIMIT_PCT to null))
        assertThat(stored.sectorConcentrationLimitPct).isEqualByComparingTo("40")
        assertThat(pending[SECTOR_CONCENTRATION_LIMIT_PCT]!!.value).isNull()

        service.at(hours(24))
        assertThat(service.effective(userId).sectorConcentrationLimitPct).isNull()
    }

    @Test
    fun `잘못된 값이 하나라도 있으면 아무것도 바꾸지 않는다`() {
        assertThatThrownBy {
            service.update(userId, mapOf(VAR_LIMIT_PCT to BigDecimal("2"), CONCENTRATION_LIMIT_PCT to BigDecimal("-1")))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.update(userId, mapOf(MAX_POSITION_COUNT to BigDecimal("2.5"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.update(userId, mapOf(VAR_LIMIT_PCT to BigDecimal("101"))) }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThat(stored.varLimitPct).isEqualByComparingTo("5")
        assertThat(pending).isEmpty()
    }
}
