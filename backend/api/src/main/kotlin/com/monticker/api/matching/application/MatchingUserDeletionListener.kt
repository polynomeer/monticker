package com.monticker.api.matching.application

import com.monticker.api.common.account.UserAccountDeletedEvent
import com.monticker.api.common.account.UserDeletionOrder
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * ADR-109 — 탈퇴하면 모의 주문·체결·사가를 지운다(가상 자금, 보존 의무 없음). 탈퇴 트랜잭션 안에서 동기로 돈다.
 *
 * 두 단계다([UserDeletionOrder]).
 * 1. [freeze] — 다른 모듈이 지우기 전에 미체결 주문·포지션·계좌 행을 `FOR UPDATE`로 잡는다. 체결의 근거는 힙 호가창이 아니라
 *    DB 행이다(ADR-048·074). 그래서 행 락이 "늦게 온 체결"을 막는다:
 *    - 지금 체결 중인 주문은 그 트랜잭션이 커밋할 때까지 기다린다. 그 체결의 paper_trades·원장은 뒤 단계 DELETE가 본다.
 *    - 이후 스위퍼(LimitOrderFiller, SKIP LOCKED)는 잠긴 행을 건너뛰고, 커밋 뒤에는 행이 없다.
 *    - 계좌 행 락은 같은 사용자의 새 BUY 제출(현금 예약 UPDATE)을, 포지션 행 락은 SELL 제출을 커밋까지 세운다.
 *    락 순서(주문 → 포지션 → 계좌)는 LimitOrderFiller·OrderSagaOrchestrator와 같다.
 * 2. [delete] — wallet·paper가 fills·orders를 참조하는 행을 지운 뒤 마지막에 돈다.
 *
 * 힙 호가창은 표시용 사본이다(체결하지 않는다 — ADR-048). 커밋 뒤에 이 pod의 사본에서 뺀다. 롤백되면 주문은 미체결로 남으므로
 * 사본도 그대로 둔다. 다른 pod의 사본은 남지만 체결에 쓰이지 않고, 재시작하면 사라진다.
 */
@Component
class MatchingUserDeletionListener(
    private val jdbc: JdbcTemplate,
    private val orderBook: MatchingOrderBookService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val LOCK_OPEN_ORDERS_SQL =
            "SELECT id FROM orders WHERE user_id = ? AND status IN ('PENDING', 'PARTIALLY_FILLED') ORDER BY id FOR UPDATE"
        const val LOCK_POSITIONS_SQL = "SELECT stock_id FROM portfolio_positions WHERE user_id = ? ORDER BY stock_id FOR UPDATE"
        const val LOCK_ACCOUNT_SQL = "SELECT user_id FROM paper_accounts WHERE user_id = ? FOR UPDATE"

        /** fills·order_sagas가 orders를 참조한다. */
        val DELETE_SQL = listOf(
            "DELETE FROM fills WHERE user_id = ?",
            "DELETE FROM order_sagas WHERE user_id = ?",
            "DELETE FROM orders WHERE user_id = ?",
        )
    }

    @EventListener
    @Order(UserDeletionOrder.MATCHING_FREEZE)
    fun freeze(event: UserAccountDeletedEvent) {
        val userId = event.userId
        jdbc.query(LOCK_OPEN_ORDERS_SQL, { rs, _ -> rs.getLong("id") }, userId)
        jdbc.query(LOCK_POSITIONS_SQL, { rs, _ -> rs.getLong("stock_id") }, userId)
        jdbc.query(LOCK_ACCOUNT_SQL, { rs, _ -> rs.getLong("user_id") }, userId)
        afterCommit {
            val evicted = orderBook.evictUser(userId)
            if (evicted > 0) log.info("[UserDeletion] evicted {} order(s) of userId={} from the order book", evicted, userId)
        }
    }

    @EventListener
    @Order(UserDeletionOrder.MATCHING)
    fun delete(event: UserAccountDeletedEvent) {
        DELETE_SQL.forEach { jdbc.update(it, event.userId) }
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { action(); return }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }
}
