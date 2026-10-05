package com.monticker.api.brokerage

import com.monticker.api.brokerage.application.ConditionalOrderFailures
import com.monticker.api.common.notification.UserNotificationCommand
import com.monticker.api.support.PostgresIntegrationTest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** ADR-065 — 발동 실패를 닫는 UPDATE … RETURNING과 알림 발행을 실제 Postgres에서 확인한다. */
class ConditionalOrderFailuresIntegrationTest : PostgresIntegrationTest() {

    private val published = mutableListOf<Any>()
    private val failures by lazy {
        ConditionalOrderFailures(
            jdbcTemplate, TransactionTemplate(DataSourceTransactionManager(dataSource)),
            ApplicationEventPublisher { published += it }, SimpleMeterRegistry(),
        )
    }

    private fun conditional(status: String, triggerType: String = "STOP_LOSS"): Pair<Long, Long> {
        val userId = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, 'f') RETURNING id", Long::class.java, "f65-${System.nanoTime()}@test.local",
        )!!
        val accountId = jdbcTemplate.queryForObject(
            "INSERT INTO brokerage_accounts (user_id, provider, account_number) VALUES (?, 'KIS', ?) RETURNING id",
            Long::class.java, userId, "F${System.nanoTime() % 100000000}",
        )!!
        val stockId = jdbcTemplate.queryForObject(
            "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, 'f', 'KOSPI', 'KRX') RETURNING id",
            Long::class.java, "F${System.nanoTime() % 100000000}",
        )!!
        val id = jdbcTemplate.queryForObject(
            """
            INSERT INTO conditional_orders (user_id, account_id, stock_id, symbol, side, trigger_type, trigger_price, order_type, quantity, status)
            VALUES (?, ?, ?, '005930', 'SELL', ?, 70000, 'MARKET', 10, ?) RETURNING id
            """.trimIndent(), Long::class.java, userId, accountId, stockId, triggerType, status,
        )!!
        return id to userId
    }

    private fun row(id: Long) = jdbcTemplate.queryForMap("SELECT status, fail_reason FROM conditional_orders WHERE id = ?", id)

    @Test
    fun `TRIGGERED를 FAILED로 닫고 그 사용자에게 알림 한 건을 남긴다`() {
        val (id, userId) = conditional("TRIGGERED")

        val closed = failures.markFailed(id, "증권사 확인 중인 같은 방향 주문(#31)이 있어 새 주문을 낼 수 없었습니다")

        assertThat(closed).isTrue()
        assertThat(row(id)).containsEntry("status", "FAILED")
            .containsEntry("fail_reason", "증권사 확인 중인 같은 방향 주문(#31)이 있어 새 주문을 낼 수 없었습니다")
        val command = published.single() as UserNotificationCommand
        assertThat(command.userId).isEqualTo(userId)
        assertThat(command.dedupKey).isEqualTo("conditional-order-failed:$id")
        assertThat(command.title).isEqualTo("005930 손절(스탑로스) 주문이 실행되지 않았습니다")
        assertThat(command.body).contains("#31").contains("다시 등록")
    }

    @Test
    fun `이미 닫힌 행은 다시 닫지 않고 알림도 다시 남기지 않는다 — 평가기와 리퍼가 겹쳐도 한 번`() {
        val (id, _) = conditional("TRIGGERED")
        failures.markFailed(id, "첫 사유")
        published.clear()

        val again = failures.markFailed(id, "두 번째 사유")

        assertThat(again).isFalse()
        assertThat(row(id)).containsEntry("fail_reason", "첫 사유")
        assertThat(published).isEmpty()
    }

    @Test
    fun `발동하지 않은(ACTIVE) 조건부 주문은 건드리지 않는다`() {
        val (id, _) = conditional("ACTIVE", triggerType = "TAKE_PROFIT")

        assertThat(failures.markFailed(id, "x")).isFalse()
        assertThat(row(id)).containsEntry("status", "ACTIVE")
        assertThat(published).isEmpty()
    }
}
