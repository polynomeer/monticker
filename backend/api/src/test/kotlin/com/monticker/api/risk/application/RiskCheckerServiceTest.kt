package com.monticker.api.risk.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.risk.domain.RiskLimit
import com.monticker.api.risk.infrastructure.RiskLimitRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.util.Optional

class RiskCheckerServiceTest {

    private val riskLimitRepo = mockk<RiskLimitRepository>()
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val riskRuleQueryService = RiskRuleQueryService(jdbc)
    private val auditLogger = RiskCheckAuditLogger(jdbc, objectMapper)
    private val service = RiskCheckerService(riskLimitRepo, riskRuleQueryService, auditLogger)

    private val userId = 1L
    private val stockId = 100L
    private val estimatedPrice = BigDecimal("1000")

    private val defaultLimits = RiskLimit(
        userId = userId,
        dailyLossLimitPct = BigDecimal("3.00"),
        concentrationLimitPct = BigDecimal("30.00"),
        varLimitPct = BigDecimal("5.00"),
        maxPositionCount = 10,
        maxHourlyOrders = 5,
    )

    /**
     * Stubs all jdbc calls with "safe" defaults so a check() run completes
     * without tripping any rule. Individual tests override specific stubs.
     */
    private fun stubSafeDefaults(limits: RiskLimit = defaultLimits) {
        every { riskLimitRepo.findByUserId(userId) } returns Optional.of(limits)

        // 1. daily pnl
        every {
            jdbc.query(match<String> { it.contains("FROM fills") }, any<RowMapper<BigDecimal>>(), userId)
        } returns listOf(BigDecimal.ZERO)

        // accountCash
        every {
            jdbc.query(match<String> { it.contains("paper_accounts") }, any<RowMapper<BigDecimal>>(), userId)
        } returns listOf(BigDecimal("10000000"))

        // 3. holdings (concentration + VaR 둘 다 이 단일 조회를 공유한다 — RiskRuleQueryService 참고)
        every {
            jdbc.queryForList(match<String> { it.contains("paper_trades") && it.contains("GROUP BY stock_id") }, userId)
        } returns emptyList()

        // 7. position count
        every {
            jdbc.queryForObject(match<String> { it.contains("COUNT(DISTINCT stock_id)") }, Long::class.java, userId, userId)
        } returns 0L

        // 8. is new stock (sum of buy/sell qty for this stock)
        every {
            jdbc.queryForObject(match<String> { it.contains("SUM(CASE WHEN side='BUY' THEN quantity ELSE -quantity END), 0)") }, Int::class.java, userId, stockId)
        } returns 0

        // 9. hourly orders
        every {
            jdbc.query(match<String> { it.contains("FROM orders") }, any<RowMapper<Long>>(), userId, any())
        } returns listOf(0L)

        // 10. log insert
        every { jdbc.update(any<String>(), *anyVararg()) } returns 1
    }

    @Test
    fun `daily loss rule fails when realized loss exceeds configured limit`() {
        stubSafeDefaults()
        // account cash 10,000,000 * 3% = 300,000 limit; loss of -400,000 exceeds it
        every {
            jdbc.query(match<String> { it.contains("FROM fills") }, any<RowMapper<BigDecimal>>(), userId)
        } returns listOf(BigDecimal("-400000"))

        val result = service.check(userId, stockId, "SELL", 1, estimatedPrice)

        val dailyLossCheck = result.checks.first { it.rule == "DailyLossRule" }
        assertThat(dailyLossCheck.passed).isFalse()
        assertThat(dailyLossCheck.detail).contains("일간 손실")
        assertThat(result.blockedBy).isEqualTo("DailyLossRule")
        assertThat(result.approved).isFalse()
    }

    @Test
    fun `daily loss rule passes when loss is within limit`() {
        stubSafeDefaults()
        every {
            jdbc.query(match<String> { it.contains("FROM fills") }, any<RowMapper<BigDecimal>>(), userId)
        } returns listOf(BigDecimal("-100000"))

        val result = service.check(userId, stockId, "SELL", 1, estimatedPrice)

        val dailyLossCheck = result.checks.first { it.rule == "DailyLossRule" }
        assertThat(dailyLossCheck.passed).isTrue()
    }

    @Test
    fun `concentration rule fails when buying pushes single stock allocation above limit`() {
        stubSafeDefaults()
        // total assets 10,000,000 cash + 0 stock value = 10,000,000
        // buying 4000 qty * 1000 price = 4,000,000 -> 40% > 30% limit
        val result = service.check(userId, stockId, "BUY", 4000, estimatedPrice)

        val concentrationCheck = result.checks.first { it.rule == "ConcentrationRule" }
        assertThat(concentrationCheck.passed).isFalse()
        assertThat(result.blockedBy).isEqualTo("ConcentrationRule")
    }

    @Test
    fun `concentration rule passes when within limit`() {
        stubSafeDefaults()
        // buying 100 qty * 1000 = 100,000 / 10,000,000 = 1% well within 30%
        val result = service.check(userId, stockId, "BUY", 100, estimatedPrice)

        val concentrationCheck = result.checks.first { it.rule == "ConcentrationRule" }
        assertThat(concentrationCheck.passed).isTrue()
    }

    @Test
    fun `var rule fails when estimated VaR exceeds limit`() {
        val tightLimits = RiskLimit(
            userId = userId,
            dailyLossLimitPct = BigDecimal("3.00"),
            concentrationLimitPct = BigDecimal("30.00"),
            varLimitPct = BigDecimal("0.01"),
            maxPositionCount = 10,
            maxHourlyOrders = 5,
        )
        stubSafeDefaults(tightLimits)
        // ADR-025 리팩터링 이후 VaR 대상 종목도 "현재 보유 중"인 종목(holdings 조회)에서만
        // 가져온다 — 예전엔 이미 청산한 종목까지 포함하는 별도 DISTINCT 조회를 썼는데,
        // 지금 포트폴리오와 무관한 과거 종목을 리스크에 반영하던 버그였다.
        every {
            jdbc.queryForList(match<String> { it.contains("paper_trades") && it.contains("GROUP BY stock_id") }, userId)
        } returns listOf(mapOf("stock_id" to stockId, "qty" to 10))

        // candles_1d returns: provide >=6 closes so the 95th-percentile branch
        // (allReturns.size >= 5) is used, with a clear negative-return tail so
        // VaR comfortably exceeds the tight 0.01% limit.
        every {
            jdbc.queryForList(match<String> { it.contains("candles_1d") }, *anyVararg())
        } returns listOf(
            mapOf("stock_id" to stockId, "close" to BigDecimal("700")),
            mapOf("stock_id" to stockId, "close" to BigDecimal("800")),
            mapOf("stock_id" to stockId, "close" to BigDecimal("900")),
            mapOf("stock_id" to stockId, "close" to BigDecimal("950")),
            mapOf("stock_id" to stockId, "close" to BigDecimal("980")),
            mapOf("stock_id" to stockId, "close" to BigDecimal("1000")),
        )

        val result = service.check(userId, stockId, "SELL", 1, estimatedPrice)

        val varCheck = result.checks.first { it.rule == "VaRRule" }
        assertThat(varCheck.passed).isFalse()
        assertThat(result.blockedBy).isEqualTo("VaRRule")
    }

    @Test
    fun `position count rule fails when at max distinct stocks and order is for new stock`() {
        val limits = RiskLimit(
            userId = userId,
            dailyLossLimitPct = BigDecimal("3.00"),
            concentrationLimitPct = BigDecimal("30.00"),
            varLimitPct = BigDecimal("5.00"),
            maxPositionCount = 3,
            maxHourlyOrders = 5,
        )
        stubSafeDefaults(limits)
        // ADR-025 리팩터링 이후 보유 종목 수(positionCount)/신규 여부 판정도 holdings
        // 스냅샷 하나로 통일됐다 — 3개 보유 중인 상태를 이 조회 하나로 표현한다.
        every {
            jdbc.queryForList(match<String> { it.contains("paper_trades") && it.contains("GROUP BY stock_id") }, userId)
        } returns listOf(
            mapOf("stock_id" to 201L, "qty" to 5),
            mapOf("stock_id" to 202L, "qty" to 5),
            mapOf("stock_id" to 203L, "qty" to 5),
        )

        val result = service.check(userId, stockId, "BUY", 10, estimatedPrice)

        val posCheck = result.checks.first { it.rule == "PositionCountRule" }
        assertThat(posCheck.passed).isFalse()
        assertThat(result.blockedBy).isEqualTo("PositionCountRule")
    }

    @Test
    fun `position count rule passes when order is for stock already held even at limit`() {
        val limits = RiskLimit(
            userId = userId,
            dailyLossLimitPct = BigDecimal("3.00"),
            concentrationLimitPct = BigDecimal("30.00"),
            varLimitPct = BigDecimal("5.00"),
            maxPositionCount = 3,
            maxHourlyOrders = 5,
        )
        stubSafeDefaults(limits)
        // 이미 보유 중인 종목(stockId)이 3개 보유 목록에 포함돼 있다 — isNewStock=false이므로
        // PositionCountRule 자체가 평가되지 않아야 한다.
        every {
            jdbc.queryForList(match<String> { it.contains("paper_trades") && it.contains("GROUP BY stock_id") }, userId)
        } returns listOf(
            mapOf("stock_id" to stockId, "qty" to 10),
            mapOf("stock_id" to 202L, "qty" to 5),
            mapOf("stock_id" to 203L, "qty" to 5),
        )

        val result = service.check(userId, stockId, "BUY", 10, estimatedPrice)

        val posCheck = result.checks.firstOrNull { it.rule == "PositionCountRule" }
        // Rule is skipped entirely (not added) when stock isn't new, so it should not appear
        // as failing — if present it must pass.
        assertThat(posCheck?.passed ?: true).isTrue()
        assertThat(result.approved).isTrue()
    }

    @Test
    fun `trading frequency rule fails when hourly order count meets or exceeds limit`() {
        stubSafeDefaults()
        every {
            jdbc.query(match<String> { it.contains("FROM orders") }, any<RowMapper<Long>>(), userId, any())
        } returns listOf(5L)

        val result = service.check(userId, stockId, "SELL", 1, estimatedPrice)

        val freqCheck = result.checks.first { it.rule == "TradingFrequencyRule" }
        assertThat(freqCheck.passed).isFalse()
        assertThat(result.blockedBy).isEqualTo("TradingFrequencyRule")
    }

    @Test
    fun `check returns approved true and blockedBy null when all rules pass`() {
        stubSafeDefaults()

        val result = service.check(userId, stockId, "BUY", 10, estimatedPrice)

        assertThat(result.approved).isTrue()
        assertThat(result.blockedBy).isNull()
        assertThat(result.severity).isEqualTo("APPROVED")
    }

    @Test
    fun `check does not throw for a user with no paper_accounts row yet and falls back to seed cash`() {
        stubSafeDefaults()
        // 한 번도 페이퍼 트레이딩을 하지 않은 신규 유저는 paper_accounts 행이 아직 없다 — 0건 조회 시
        // queryForObject였다면 EmptyResultDataAccessException으로 GlobalExceptionHandler catch-all에
        // 잡혀 안내 메시지 없는 500이 났던 버그. query+firstOrNull로 기본 시드머니 폴백을 검증한다.
        every {
            jdbc.query(match<String> { it.contains("paper_accounts") }, any<RowMapper<BigDecimal>>(), userId)
        } returns emptyList()

        val result = service.check(userId, stockId, "BUY", 10, estimatedPrice)

        assertThat(result.approved).isTrue()
    }

    @Test
    fun `check returns approved false and blockedBy set with BLOCKED severity when any rule fails`() {
        stubSafeDefaults()
        every {
            jdbc.query(match<String> { it.contains("FROM orders") }, any<RowMapper<Long>>(), userId, any())
        } returns listOf(99L)

        val result = service.check(userId, stockId, "BUY", 10, estimatedPrice)

        assertThat(result.approved).isFalse()
        assertThat(result.blockedBy).isNotNull()
        assertThat(result.severity).isEqualTo("BLOCKED")
    }

    @Test
    fun `check inserts a risk check log row via jdbc update`() {
        stubSafeDefaults()

        service.check(userId, stockId, "BUY", 10, estimatedPrice)

        verify {
            jdbc.update(match<String> { it.contains("risk_check_logs") }, *anyVararg())
        }
    }
}
