package com.monticker.api.brokerage.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp

class ConditionalOrderExpirySchedulerTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val scheduler = ConditionalOrderExpiryScheduler(jdbc)

    @Test
    fun `만료 시각이 지난 ACTIVE 주문만 대상으로 원자적 UPDATE를 실행한다`() {
        every {
            jdbc.update(
                match<String> {
                    it.contains("UPDATE conditional_orders") &&
                        it.contains("SET status = 'EXPIRED'") &&
                        it.contains("WHERE status = 'ACTIVE'") &&
                        it.contains("expires_at < ?")
                },
                any<Timestamp>(), any<Timestamp>(),
            )
        } returns 3

        scheduler.expireStaleOrders()

        verify { jdbc.update(any<String>(), any<Timestamp>(), any<Timestamp>()) }
    }
}
