package com.monticker.api.matching.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.account.UserDeletionOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionSynchronizationManager

class MatchingUserDeletionListenerTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val orderBook = mockk<MatchingOrderBookService>(relaxed = true)
    private val listener = MatchingUserDeletionListener(jdbc, orderBook)

    @AfterEach
    fun clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization()
    }

    @Test
    fun `freeze는 주문 → 포지션 → 계좌 순으로 행을 잠근다`() {
        listener.freeze(UserAccountDeletedEvent(42L))

        verifyOrder {
            jdbc.query(MatchingUserDeletionListener.LOCK_OPEN_ORDERS_SQL, any<RowMapper<Long>>(), 42L)
            jdbc.query(MatchingUserDeletionListener.LOCK_POSITIONS_SQL, any<RowMapper<Long>>(), 42L)
            jdbc.query(MatchingUserDeletionListener.LOCK_ACCOUNT_SQL, any<RowMapper<Long>>(), 42L)
        }
        assertThat(MatchingUserDeletionListener.LOCK_OPEN_ORDERS_SQL)
            .contains("status IN ('PENDING', 'PARTIALLY_FILLED')").endsWith("FOR UPDATE")
    }

    @Test
    fun `트랜잭션 안에서는 호가창 사본을 커밋 뒤에만 비운다`() {
        TransactionSynchronizationManager.initSynchronization()

        listener.freeze(UserAccountDeletedEvent(42L))
        verify(exactly = 0) { orderBook.evictUser(any()) }

        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        verify(exactly = 1) { orderBook.evictUser(42L) }
    }

    @Test
    fun `롤백되면 호가창 사본을 그대로 둔다`() {
        TransactionSynchronizationManager.initSynchronization()

        listener.freeze(UserAccountDeletedEvent(42L))
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCompletion(1) }

        verify(exactly = 0) { orderBook.evictUser(any()) }
    }

    @Test
    fun `delete는 체결과 사가를 주문보다 먼저 지운다`() {
        every { jdbc.update(any<String>(), any<Long>()) } returns 1

        listener.delete(UserAccountDeletedEvent(42L))

        verifyOrder {
            jdbc.update("DELETE FROM fills WHERE user_id = ?", 42L)
            jdbc.update("DELETE FROM order_sagas WHERE user_id = ?", 42L)
            jdbc.update("DELETE FROM orders WHERE user_id = ?", 42L)
        }
    }

    @Test
    fun `freeze는 맨 앞쪽, delete는 맨 뒤에 돈다`() {
        val freeze = MatchingUserDeletionListener::class.java.getMethod("freeze", UserAccountDeletedEvent::class.java)
        val delete = MatchingUserDeletionListener::class.java.getMethod("delete", UserAccountDeletedEvent::class.java)
        assertThat(freeze.getAnnotation(Order::class.java).value).isEqualTo(UserDeletionOrder.MATCHING_FREEZE)
        assertThat(delete.getAnnotation(Order::class.java).value).isEqualTo(UserDeletionOrder.MATCHING)
        assertThat(UserDeletionOrder.MATCHING_FREEZE).isLessThan(UserDeletionOrder.WALLET)
    }
}
