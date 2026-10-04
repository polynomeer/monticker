package com.monticker.api.brokerage

import com.monticker.api.brokerage.application.BrokerageOrderReconciler
import com.monticker.api.brokerage.application.BrokerageService
import com.monticker.api.brokerage.application.ConditionalOrderReaper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-056 — 단위 테스트가 목으로 대신한 SQL을 실제 Postgres(Flyway V51까지)에서 확인한다.
 */
class BrokerageOrderUnknownOutcomeSqlIntegrationTest : PostgresIntegrationTest() {

    private val tx by lazy { TransactionTemplate(DataSourceTransactionManager(dataSource)) }

    private fun fixture(): Pair<Long, Long> {
        val userId = jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, 'u') RETURNING id", Long::class.java, "u56-${System.nanoTime()}@test.local",
        )!!
        val accountId = jdbcTemplate.queryForObject(
            "INSERT INTO brokerage_accounts (user_id, provider, account_number) VALUES (?, 'KIS', ?) RETURNING id",
            Long::class.java, userId, "A${System.nanoTime() % 100000000}",
        )!!
        return userId to accountId
    }

    private fun insertOrder(userId: Long, accountId: Long, status: String, clientOrderId: String?, agoSeconds: Long = 60): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO brokerage_orders (user_id, account_id, symbol, side, order_type, quantity, status, client_order_id, submitted_at)
            VALUES (?, ?, '005930', 'SELL', 'MARKET', 10, ?, ?, now() - make_interval(secs => ?)) RETURNING id
            """.trimIndent(),
            Long::class.java, userId, accountId, status, clientOrderId, agoSeconds.toDouble(),
        )!!

    @Test
    fun `V51 — PENDING_SUBMIT·UNKNOWN 상태가 허용되고 그 밖의 값은 막힌다`() {
        val (u, a) = fixture()
        insertOrder(u, a, "PENDING_SUBMIT", null)
        insertOrder(u, a, "UNKNOWN", null)
        assertThatThrownBy { insertOrder(u, a, "LOST", null) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `V51 — client_order_id는 유일하다(같은 조건부 주문의 두 번째 제출을 DB가 막는다), NULL은 여러 개 허용`() {
        val (u, a) = fixture()
        val coid = "co-${System.nanoTime()}"
        insertOrder(u, a, "PENDING_SUBMIT", coid)
        assertThatThrownBy { insertOrder(u, a, "PENDING_SUBMIT", coid) }.isInstanceOf(DataIntegrityViolationException::class.java)
        insertOrder(u, a, "SUBMITTED", null)
        insertOrder(u, a, "SUBMITTED", null)
    }

    @Test
    fun `V51 — 리밸런싱 leg에 UNKNOWN을 기록할 수 있다`() {
        val def = jdbcTemplate.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'rebalance_execution_legs_status_check'", String::class.java,
        )
        assertThat(def).contains("UNKNOWN")
    }

    @Test
    fun `대조 행 획득은 SKIP LOCKED — 다른 레플리카가 잡은 행은 빈 결과로 건너뛴다`() {
        val (u, a) = fixture()
        val id = insertOrder(u, a, "UNKNOWN", null)
        val sql = "SELECT id FROM brokerage_orders WHERE id = ? AND status IN ('PENDING_SUBMIT','UNKNOWN') FOR UPDATE SKIP LOCKED"
        val held = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val holder = pool.submit {
                tx.executeWithoutResult {
                    assertThat(jdbcTemplate.queryForList(sql, Long::class.java, id)).containsExactly(id)
                    held.countDown(); release.await(10, TimeUnit.SECONDS)
                }
            }
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue()
            val seen = tx.execute { jdbcTemplate.queryForList(sql, Long::class.java, id) }
            assertThat(seen).isEmpty()
            release.countDown(); holder.get(10, TimeUnit.SECONDS)
            assertThat(tx.execute { jdbcTemplate.queryForList(sql, Long::class.java, id) }).containsExactly(id)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `대조 배치는 백오프 중인 행을 건너뛴다 — 해소 불가 행이 상한을 차지해 새 주문이 굶지 않게`() {
        val (u, a) = fixture()
        val stuck = insertOrder(u, a, "UNKNOWN", null, agoSeconds = 3600)
        val fresh = insertOrder(u, a, "UNKNOWN", null, agoSeconds = 60)
        jdbcTemplate.update("UPDATE brokerage_orders SET next_reconcile_at = now() + interval '5 minutes' WHERE id = ?", stuck)

        // 실제 스케줄러 쿼리를 돌리고, 어떤 행을 대조하려 했는지만 기록한다.
        val due = mutableListOf<Long>()
        val service = mockk<BrokerageService> {
            every { reconcileUnresolved(any(), any()) } answers { due += firstArg<Long>(); BrokerageService.ReconcileResult.WAITING }
        }
        BrokerageOrderReconciler(jdbcTemplate, service, SimpleMeterRegistry()).reconcileDue()

        assertThat(due).contains(fresh).doesNotContain(stuck)
    }

    @Test
    fun `사용자별 advisory lock SQL이 실제 파라미터 타입으로 실행된다`() {
        tx.executeWithoutResult {
            jdbcTemplate.query("SELECT pg_advisory_xact_lock(?, (? % 2147483647)::int)", { _ -> }, 56_001, 9_999_999_999L)
        }
    }

    @Test
    fun `리퍼 — V51 이후 발동분 중 주문 행이 없는 것은 FAILED(미전송), V51 이전 발동분은 건드리지 않는다`() {
        val (u, a) = fixture()
        val stockId = jdbcTemplate.queryForObject(
            "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, 'r', 'KOSPI', 'KRX') RETURNING id",
            Long::class.java, "R${System.nanoTime() % 100000000}",
        )!!
        fun conditional(triggeredSql: String): Long = jdbcTemplate.queryForObject(
            """
            INSERT INTO conditional_orders (user_id, account_id, stock_id, symbol, side, trigger_type, trigger_price, order_type, quantity, status, triggered_at)
            VALUES (?, ?, ?, '005930', 'SELL', 'STOP_LOSS', 70000, 'MARKET', 10, 'TRIGGERED', $triggeredSql) RETURNING id
            """.trimIndent(), Long::class.java, u, a, stockId,
        )!!
        // 새 컨테이너는 V51을 방금 설치했다 — "V51 이후 + 2분 경과" 행을 만들 수 있게 설치 시각을 하루 전으로 옮긴다.
        jdbcTemplate.update("UPDATE flyway_schema_history SET installed_on = now() - interval '1 day' WHERE version = '51'")
        val recent = conditional("now() - interval '3 minutes'")
        val legacy = conditional("(SELECT installed_on FROM flyway_schema_history WHERE version = '51') - interval '1 hour'")

        ConditionalOrderReaper(jdbcTemplate).reapStuckTriggered()

        fun status(id: Long) = jdbcTemplate.queryForObject("SELECT status FROM conditional_orders WHERE id = ?", String::class.java, id)
        assertThat(status(recent)).isEqualTo("FAILED")
        assertThat(status(legacy)).isEqualTo("TRIGGERED")
    }
}
