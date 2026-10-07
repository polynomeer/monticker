package com.monticker.api.risk.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RiskDecisionQueryServiceTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val service = RiskDecisionQueryService(jdbc)

    private fun decision(id: Long) = RiskDecision(id, Instant.EPOCH, "PAPER", 1L, "005930", "삼성전자", "BUY", 10, "ConcentrationRule", "집중도 35% / 한도 30%")

    @Test
    fun `본인의 차단 기록만 size+1로 읽어 다음 페이지 유무를 안다`() {
        every { jdbc.query(match<String> { it.contains("approved = false") }, any<RowMapper<RiskDecision>>(), 9L, 3, 4) } returns
            listOf(decision(1), decision(2), decision(3))

        val page = service.blocked(9L, page = 2, size = 2)

        assertThat(page.items.map { it.id }).containsExactly(1L, 2L)
        assertThat(page.hasNext).isTrue()
        verify { jdbc.query(match<String> { it.contains("WHERE l.user_id = ?") }, any<RowMapper<RiskDecision>>(), 9L, 3, 4) }
    }

    @Test
    fun `과도한 페이지 크기는 거부한다`() {
        assertThatThrownBy { service.blocked(9L, 0, 500) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.blocked(9L, -1, 20) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `이번 달은 KST 기준으로 센다`() {
        service.clock = Clock.fixed(Instant.parse("2026-09-30T16:00:00Z"), ZoneOffset.UTC) // KST 10-01 01:00
        every { jdbc.query(match<String> { it.contains("COUNT(*)") }, any<RowMapper<Long>>(), 9L, any<Timestamp>()) } returns listOf(4L)

        val s = service.summary(9L)

        assertThat(s.blockedThisMonth).isEqualTo(4L)
        assertThat(s.monthStart).isEqualTo(Instant.parse("2026-09-30T15:00:00Z")) // 10-01 00:00 KST
    }

    // 보안 리뷰 — 설정 화면의 사전 점검(POST /api/risk/check)은 주문이 아니다. 차단 기록·이번 달 차단 수에 넣지 않는다.
    @Test
    fun `사전 점검(dry run)은 차단 기록과 이번 달 차단 수에서 뺀다`() {
        every { jdbc.query(any<String>(), any<RowMapper<RiskDecision>>(), *anyVararg()) } returns emptyList()
        every { jdbc.query(match<String> { it.contains("COUNT(*)") }, any<RowMapper<Long>>(), 9L, any<Timestamp>()) } returns listOf(0L)

        service.blocked(9L, 0, 20)
        service.summary(9L)

        verify { jdbc.query(match<String> { it.contains("approved = false") && it.contains("dry_run = false") }, any<RowMapper<RiskDecision>>(), *anyVararg()) }
        verify { jdbc.query(match<String> { it.contains("COUNT(*)") && it.contains("dry_run = false") }, any<RowMapper<Long>>(), 9L, any<Timestamp>()) }
    }
}
