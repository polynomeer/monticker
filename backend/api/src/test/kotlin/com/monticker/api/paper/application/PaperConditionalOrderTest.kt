package com.monticker.api.paper.application

import com.monticker.api.matching.submit.OrderOrigin
import com.monticker.api.common.aop.RiskLimitException
import com.monticker.api.matching.submit.MarketOrderResult
import com.monticker.api.matching.submit.OrderSubmitter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** ADR-075 — 모의투자 조건부 주문: 등록 검증, 발동(행 락·멱등 키·OCO), 거부 시 FAILED. */
class PaperConditionalOrderTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val service = PaperConditionalOrderService(jdbc)

    private fun leg(t: String, p: String) = PaperConditionalLeg(t, BigDecimal(p))

    @Test
    fun `take-profit and stop-loss are SELL-only`() {
        assertThatThrownBy {
            service.create(1L, PaperConditionalRequest(5L, "BUY", 1, listOf(leg("STOP_LOSS", "900"))))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("BUY")
    }

    @Test
    fun `an OCO needs the take-profit above the stop-loss`() {
        assertThatThrownBy {
            service.create(1L, PaperConditionalRequest(5L, "SELL", 1, listOf(leg("TAKE_PROFIT", "900"), leg("STOP_LOSS", "950"))))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("익절가")
    }

    @Test
    fun `a SELL condition needs the shares at registration`() {
        every { jdbc.query(match<String> { it.contains("FROM portfolio_positions") }, any<RowMapper<Int>>(), 1L, 5L) } returns listOf(2)
        assertThatThrownBy {
            service.create(1L, PaperConditionalRequest(5L, "SELL", 3, listOf(leg("STOP_LOSS", "900"))))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("보유 수량 부족")
        verify(exactly = 0) { jdbc.queryForObject(match<String> { it.startsWith("INSERT") }, Long::class.java, *anyVararg()) }
    }

    @Test
    fun `two legs share one OCO group id`() {
        every { jdbc.query(match<String> { it.contains("FROM portfolio_positions") }, any<RowMapper<Int>>(), 1L, 5L) } returns listOf(10)
        every { jdbc.queryForObject(match<String> { it.contains("count(*)") }, Long::class.java, 1L) } returns 0L
        val groups = mutableListOf<Any?>()
        every { jdbc.queryForObject(match<String> { it.startsWith("INSERT") }, Long::class.java, *anyVararg()) } answers {
            @Suppress("UNCHECKED_CAST")
            val args = (it.invocation.args[2] as Array<Any?>)
            groups.add(args[6]); groups.size.toLong()
        }

        service.create(1L, PaperConditionalRequest(5L, "SELL", 10, listOf(leg("TAKE_PROFIT", "1100"), leg("STOP_LOSS", "900"))))

        assertThat(groups).hasSize(2)
        assertThat(groups[0]).isInstanceOf(UUID::class.java).isEqualTo(groups[1])
    }

    @Test
    fun `the live-order cap is enforced`() {
        every { jdbc.query(match<String> { it.contains("FROM portfolio_positions") }, any<RowMapper<Int>>(), 1L, 5L) } returns listOf(10)
        every { jdbc.queryForObject(match<String> { it.contains("count(*)") }, Long::class.java, 1L) } returns 49L
        assertThatThrownBy {
            service.create(1L, PaperConditionalRequest(5L, "SELL", 1, listOf(leg("TAKE_PROFIT", "1100"), leg("STOP_LOSS", "900"))))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("최대")
    }

    // ── 발동 ─────────────────────────────────────────────────────────

    private val submitter = mockk<OrderSubmitter>()
    private val firer = PaperConditionalOrderFirer(jdbc, submitter)

    private fun stubRow(trigger: String = "STOP_LOSS", price: String = "900", oco: UUID? = null, locked: Boolean = true) {
        every { jdbc.query(PaperConditionalOrderFirer.LOCK_SQL, any<RowMapper<Any>>(), 9L) } answers {
            if (!locked) emptyList() else {
                val rs = mockk<java.sql.ResultSet>()
                every { rs.getLong("id") } returns 9L
                every { rs.getLong("user_id") } returns 1L
                every { rs.getLong("stock_id") } returns 5L
                every { rs.getString("side") } returns "SELL"
                every { rs.getString("trigger_type") } returns trigger
                every { rs.getBigDecimal("trigger_price") } returns BigDecimal(price)
                every { rs.getInt("quantity") } returns 4
                every { rs.getObject("oco_group_id", UUID::class.java) } returns oco
                @Suppress("UNCHECKED_CAST")
                listOf((secondArg<RowMapper<Any>>()).mapRow(rs, 0))
            }
        }
    }

    private fun stubPrice(p: String, at: Instant = Instant.now()) {
        every { jdbc.query(PaperConditionalOrderFirer.LATEST_PRICE_SQL, any<RowMapper<com.monticker.api.common.domain.LatestClose>>(), 5L) } returns
            listOf(com.monticker.api.common.domain.LatestClose(BigDecimal(p), at))
    }

    // 보안 리뷰 — 오래된 1분봉으로 손절·익절을 발동하지 않는다(시세가 끊긴 동안의 마지막 값). 이번 주기를 건너뛴다.
    @Test
    fun `does not fire on a stale candle`() {
        stubRow(); stubPrice("800", at = Instant.now().minus(com.monticker.api.common.domain.CandleFreshness.MAX_AGE).minusSeconds(60))
        assertThat(firer.fire(9L)).isEqualTo(PaperConditionalOutcome.NOT_TRIGGERED)
        verify(exactly = 0) { submitter.submitMarket(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `trigger candidates ignore stale candles`() {
        assertThat(PaperConditionalOrderTrigger.CANDIDATES_SQL)
            .contains("candle_time >= now() - interval '${com.monticker.api.common.domain.CandleFreshness.MAX_AGE_SQL}'")
    }

    @Test
    fun `fires a triggered stop-loss once with an idempotency key and cancels the OCO sibling`() {
        val group = UUID.randomUUID()
        stubRow(oco = group); stubPrice("880")
        every { submitter.submitMarket(1L, 5L, "SELL", 4, OrderOrigin.conditional(9L), "PCO:9") } returns MarketOrderResult(
            orderId = 70L, fillId = 71L, stockId = 5L, side = "SELL", quantity = 4,
            fillPrice = BigDecimal("880"), amount = BigDecimal("3520"), filledAt = Instant.now(),
        )

        assertThat(firer.fire(9L)).isEqualTo(PaperConditionalOutcome.EXECUTED)

        verify { jdbc.update(match<String> { it.contains("SET status = 'EXECUTED'") }, 70L, 9L) }
        verify { jdbc.update(match<String> { it.contains("OCO 상대 체결") }, group, 9L) }
    }

    @Test
    fun `does not fire when the price is no longer past the trigger`() {
        stubRow(); stubPrice("901")
        assertThat(firer.fire(9L)).isEqualTo(PaperConditionalOutcome.NOT_TRIGGERED)
        verify(exactly = 0) { submitter.submitMarket(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips a row another pod holds or that was cancelled`() {
        stubRow(locked = false)
        assertThat(firer.fire(9L)).isEqualTo(PaperConditionalOutcome.SKIPPED)
        verify(exactly = 0) { submitter.submitMarket(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a rejected order is recorded as FAILED, not retried every sweep`() {
        val firerMock = mockk<PaperConditionalOrderFirer>(relaxed = true)
        every { firerMock.fire(9L) } throws RiskLimitException("일간 손실 한도 초과")
        every { jdbc.query(match<String> { it.contains("FROM paper_conditional_orders co") }, any<RowMapper<Long>>()) } returns listOf(9L)

        PaperConditionalOrderTrigger(jdbc, firerMock).sweep()

        verify { firerMock.markFailed(9L, match { it.contains("일간 손실") }) }
    }
}
