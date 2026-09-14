package com.monticker.api.wallet.application

import com.monticker.api.matching.events.OrderCancelledEvent
import com.monticker.api.wallet.domain.LedgerEvent
import com.monticker.api.wallet.domain.LedgerEventType
import com.monticker.api.wallet.infrastructure.LedgerEventRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

class OrderFilledEventListenerTest {

    private val ledgerRepo = mockk<LedgerEventRepository>()
    private val jdbc = mockk<JdbcTemplate>()
    private val listener = OrderFilledEventListener(ledgerRepo, jdbc)

    private fun cancelled(refund: String) = OrderCancelledEvent(
        orderId = 10L, userId = 1L, stockId = 100L, side = "BUY", refundAmount = BigDecimal(refund),
    )

    // ADR-043 — 예약금 반환은 실현된 현금 이동이 아니다. 제출 시 예약이 원장에 없었으므로 DEPOSIT으로 적으면
    // 원장 합이 잔고보다 커져 대사가 어긋난다. CASH_UNRESERVED는 타임라인에는 남되 현금 합계에서 제외된다.
    @Test
    fun `a cancel refund is recorded as CASH_UNRESERVED, not as a DEPOSIT`() {
        val slot = slot<LedgerEvent>()
        every { ledgerRepo.save(capture(slot)) } answers { slot.captured }
        every { jdbc.queryForObject(any<String>(), BigDecimal::class.java, 1L) } returns BigDecimal("9800000")

        listener.onOrderCancelled(cancelled("200000"))

        assertThat(slot.captured.eventType).isEqualTo(LedgerEventType.CASH_UNRESERVED)
        assertThat(slot.captured.amount).isEqualByComparingTo(BigDecimal("200000"))
        assertThat(slot.captured.balanceAfter).isEqualByComparingTo(BigDecimal("9800000"))
        assertThat(LedgerReconciliationService.CASH_EVENT_TYPES).doesNotContain("CASH_UNRESERVED")
    }

    @Test
    fun `a cancel with nothing to refund writes no ledger row`() {
        listener.onOrderCancelled(cancelled("0"))

        verify(exactly = 0) { ledgerRepo.save(any()) }
    }
}
