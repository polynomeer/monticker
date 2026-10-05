package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.domain.BrokerageOrderStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

/** ADR-056 — 발동 뒤 결말이 없는 조건부 주문을 co-<id> 주문 행의 유무·상태로 정리한다. */
class ConditionalOrderReaperTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val reaper = ConditionalOrderReaper(jdbc)

    private fun givenOrderRow(row: Pair<Long, BrokerageOrderStatus>?) {
        every { jdbc.query(match<String> { it.contains("client_order_id = ?") }, any<RowMapper<Pair<Long, BrokerageOrderStatus>>>(), "co-5") } returns listOfNotNull(row)
    }

    private fun verifyResolved(status: String, reason: String?, orderId: Long?) =
        verify { jdbc.update(match<String> { it.contains("WHERE id = ? AND status = 'TRIGGERED'") }, status, reason, orderId, any(), 5L) }

    @Test
    fun `주문 행이 없으면 증권사 호출이 없었다 — FAILED(미전송)`() {
        givenOrderRow(null)
        reaper.reap(5L)
        verifyResolved("FAILED", "발동 중 중단 — 주문 미전송 확인", null)
    }

    @Test
    fun `주문이 접수·체결됐으면 EXECUTED`() {
        givenOrderRow(9L to BrokerageOrderStatus.FILLED)
        reaper.reap(5L)
        verifyResolved("EXECUTED", null, 9L)
    }

    @Test
    fun `주문이 거부됐으면 FAILED`() {
        givenOrderRow(9L to BrokerageOrderStatus.REJECTED)
        reaper.reap(5L)
        verifyResolved("FAILED", "주문 REJECTED", 9L)
    }

    @Test
    fun `주문 결과가 아직 불명이면 건드리지 않는다 — 대조 잡이 먼저다`() {
        givenOrderRow(9L to BrokerageOrderStatus.UNKNOWN)
        reaper.reap(5L)
        verify(exactly = 0) { jdbc.update(match<String> { it.contains("status = 'TRIGGERED'") }, *anyVararg()) }
    }
}
