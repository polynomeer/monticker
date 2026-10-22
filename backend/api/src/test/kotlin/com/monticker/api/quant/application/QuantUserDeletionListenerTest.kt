package com.monticker.api.quant.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.quant.domain.RuleSetDocument
import com.monticker.api.quant.infrastructure.RuleSetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager

class QuantUserDeletionListenerTest {

    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val ruleSets = mockk<RuleSetRepository>(relaxed = true)
    private val listener = QuantUserDeletionListener(jdbc, ruleSets)

    private fun doc(id: String) = mockk<RuleSetDocument> { every { this@mockk.id } returns id }

    @AfterEach
    fun clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization()
    }

    private fun stub(mongoIds: List<String>, marketIds: List<String>) {
        every { ruleSets.findAllByUserId(42L) } returns mongoIds.map(::doc)
        every { jdbc.queryForList(QuantUserDeletionListener.MARKET_RULESET_IDS_SQL, String::class.java, 42L) } returns marketIds
    }

    @Test
    fun `구독을 해지하고 내 전략은 판매 중지하며 룰셋 산출물을 신호부터 지운다`() {
        stub(listOf("a", "b"), listOf("b", "c"))   // c는 Mongo 문서가 이미 없는 마켓 행

        listener.on(UserAccountDeletedEvent(42L))

        verifyOrder {
            jdbc.update(match<String> { it.contains("DELETE FROM strategy_subscriptions WHERE user_id = ?") && it.contains("subscribe_count - 1") }, 42L)
            jdbc.update(match<String> { it.contains("SET delisted_at = now(), subscribe_count = 0") && it.contains("DELETE FROM strategy_subscriptions WHERE market_id IN") }, 42L)
            jdbc.update(match<String> { it.startsWith("DELETE FROM quant_signals WHERE rule_set_id IN (?,?,?)") && it.contains("forward_test_id IN") }, "a", "b", "c", "a", "b", "c")
            jdbc.update("DELETE FROM quant_forward_tests WHERE rule_set_id IN (?,?,?)", "a", "b", "c")
            jdbc.update("DELETE FROM quant_backtest_results WHERE rule_set_id IN (?,?,?)", "a", "b", "c")
        }
        verify(exactly = 0) { jdbc.update(match<String> { it.contains("DELETE FROM strategy_market") }, *anyVararg()) }
        verify(exactly = 1) { ruleSets.deleteAllById(listOf("a", "b")) }   // 트랜잭션 밖이면 바로
    }

    @Test
    fun `Mongo 룰셋 문서는 커밋 뒤에만 지운다`() {
        stub(listOf("a"), emptyList())
        TransactionSynchronizationManager.initSynchronization()

        listener.on(UserAccountDeletedEvent(42L))
        verify(exactly = 0) { ruleSets.deleteAllById(any()) }

        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        verify(exactly = 1) { ruleSets.deleteAllById(listOf("a")) }
    }

    @Test
    fun `룰셋이 없으면 산출물 DELETE를 하지 않는다`() {
        stub(emptyList(), emptyList())

        listener.on(UserAccountDeletedEvent(42L))

        verify(exactly = 0) { jdbc.update(match<String> { it.contains("quant_") }, *anyVararg()) }
        verify(exactly = 0) { ruleSets.deleteAllById(any()) }
    }
}
