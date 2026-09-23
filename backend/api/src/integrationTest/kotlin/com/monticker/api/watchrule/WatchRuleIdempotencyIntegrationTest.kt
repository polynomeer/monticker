package com.monticker.api.watchrule

import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-051 — "정확히 한 번 체결"의 증명.
 *
 * [com.monticker.api.watchrule.application.WatchRuleExecutor]의 사전 조회(`existsBy…`)는 빠른 경로일 뿐
 * 방어선이 아니다. 두 컨슈머 스레드가 같은 이벤트를 동시에 집으면 둘 다 조회를 통과한다 — MockK 단위
 * 테스트는 호출을 순차 실행하므로 이 레이스를 원리적으로 재현하지 못한다.
 * 실제로 중복 체결을 막는 것은 DB의 두 유니크 인덱스뿐이고, 이 테스트는 그것을 실제 동시 스레드로 친다.
 *
 * 불변조건: **어떤 장애 조합에서도 (룰, 이벤트) 하나당 주문은 최대 하나, 기록도 최대 하나.**
 */
class WatchRuleIdempotencyIntegrationTest : PostgresIntegrationTest() {

    private fun createUser(tag: String): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
            Long::class.java, "$tag-${System.nanoTime()}@test.local", tag,
        )!!

    private fun anyStockId(): Long =
        jdbcTemplate.queryForObject("SELECT id FROM stocks ORDER BY id LIMIT 1", Long::class.java)!!

    private fun createRule(userId: Long, stockId: Long, eventType: String = "VOLUME_SURGE"): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity)
            VALUES (?, ?, ?, 'BUY', 10) RETURNING id
            """.trimIndent(),
            Long::class.java, userId, stockId, eventType,
        )!!

    private fun insertOrder(userId: Long, stockId: Long, key: String?): Long? =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO orders (user_id, stock_id, side, order_type, quantity, status, idempotency_key)
            VALUES (?, ?, 'BUY', 'MARKET', 10, 'FILLED', ?) RETURNING id
            """.trimIndent(),
            Long::class.java, userId, stockId, key,
        )

    private fun insertExecution(ruleId: Long, userId: Long, eventId: Long): Long? =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO watch_rule_executions (watch_rule_id, user_id, stock_event_id, status)
            VALUES (?, ?, ?, 'EXECUTED') RETURNING id
            """.trimIndent(),
            Long::class.java, ruleId, userId, eventId,
        )

    /** 같은 작업을 N개 스레드가 동시에 시도하고, 성공한 횟수를 돌려준다. */
    private fun raceCount(threads: Int, action: () -> Unit): Int {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val outcomes = ConcurrentHashMap<Int, Boolean>()
        repeat(threads) { i ->
            pool.submit {
                start.await()
                outcomes[i] = runCatching { action() }.isSuccess
                done.countDown()
            }
        }
        start.countDown()
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        return outcomes.values.count { it }
    }

    // 장애 시나리오 — 아웃박스가 같은 이벤트를 10번 재전달했고, 10개 컨슈머 스레드가 동시에 집었다.
    @Test
    fun `ten threads submitting one idempotency key create exactly one order`() {
        val userId = createUser("wr-order-race")
        val stockId = anyStockId()
        val key = "WR:1:${System.nanoTime()}"

        val succeeded = raceCount(10) { insertOrder(userId, stockId, key) }

        assertThat(succeeded).isEqualTo(1)
        val orders = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE idempotency_key = ?", Int::class.java, key,
        )
        assertThat(orders).isEqualTo(1)
    }

    @Test
    fun `ten threads recording one rule-event pair create exactly one execution row`() {
        val userId = createUser("wr-exec-race")
        val ruleId = createRule(userId, anyStockId())
        val eventId = System.nanoTime()

        val succeeded = raceCount(10) { insertExecution(ruleId, userId, eventId) }

        assertThat(succeeded).isEqualTo(1)
        val rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM watch_rule_executions WHERE watch_rule_id = ? AND stock_event_id = ?",
            Int::class.java, ruleId, eventId,
        )
        assertThat(rows).isEqualTo(1)
    }

    @Test
    fun `a second submission with the same key is rejected by the database not by a race`() {
        val userId = createUser("wr-order-dup")
        val stockId = anyStockId()
        val key = "WR:2:${System.nanoTime()}"

        insertOrder(userId, stockId, key)

        assertThat(runCatching { insertOrder(userId, stockId, key) }.exceptionOrNull())
            .isInstanceOf(DuplicateKeyException::class.java)
    }

    // 사용자가 화면에서 직접 낸 주문은 키가 없다 — 부분 인덱스라 제약을 받지 않아야 한다.
    // (전체 유니크였다면 두 번째 주문부터 전부 실패한다.)
    @Test
    fun `orders without an idempotency key are not constrained`() {
        val userId = createUser("wr-order-nullkey")
        val stockId = anyStockId()

        val succeeded = raceCount(5) { insertOrder(userId, stockId, null) }

        assertThat(succeeded).isEqualTo(5)
    }

    @Test
    fun `distinct rule-event pairs are all recorded`() {
        val userId = createUser("wr-exec-distinct")
        val ruleId = createRule(userId, anyStockId())
        val base = System.nanoTime()

        (0 until 5).forEach { insertExecution(ruleId, userId, base + it) }

        val rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM watch_rule_executions WHERE watch_rule_id = ?", Int::class.java, ruleId,
        )
        assertThat(rows).isEqualTo(5)
    }

    // 스키마가 지켜야 하는 것들 — 애플리케이션 검증을 우회해 들어와도 DB가 막는다.
    @Test
    fun `the schema rejects a non-positive quantity`() {
        val userId = createUser("wr-qty")
        val stockId = anyStockId()

        assertThat(
            runCatching {
                jdbcTemplate.update(
                    """
                    INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity)
                    VALUES (?, ?, 'VOLUME_SURGE', 'BUY', 0)
                    """.trimIndent(), userId, stockId,
                )
            }.isFailure
        ).isTrue()
    }

    @Test
    fun `the schema rejects an unknown side`() {
        val userId = createUser("wr-side")
        val stockId = anyStockId()

        assertThat(
            runCatching {
                jdbcTemplate.update(
                    """
                    INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity)
                    VALUES (?, ?, 'VOLUME_SURGE', 'HOLD', 10)
                    """.trimIndent(), userId, stockId,
                )
            }.isFailure
        ).isTrue()
    }

    @Test
    fun `deleting a rule removes its execution history`() {
        val userId = createUser("wr-cascade")
        val ruleId = createRule(userId, anyStockId())
        insertExecution(ruleId, userId, System.nanoTime())

        jdbcTemplate.update("DELETE FROM watch_rules WHERE id = ?", ruleId)

        val rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM watch_rule_executions WHERE watch_rule_id = ?", Int::class.java, ruleId,
        )
        assertThat(rows).isEqualTo(0)
    }

    /**
     * 불변조건 — 멱등 키가 막아준 덕분에 "중복 전달"이 자금에 닿지 않는다.
     * 현금 차감을 주문 삽입과 같은 트랜잭션에 묶고, 같은 키로 10번 시도해도 차감이 1회인지 본다.
     */
    @Test
    fun `duplicate delivery never debits the account twice`() {
        val userId = createUser("wr-cash-invariant")
        val stockId = anyStockId()
        val initialCash = BigDecimal("1000000")
        jdbcTemplate.update("INSERT INTO paper_accounts (user_id, cash) VALUES (?, ?)", userId, initialCash)
        val key = "WR:3:${System.nanoTime()}"
        val debit = BigDecimal("10000")

        raceCount(10) {
            // 주문 삽입이 유니크에 걸리면 예외가 나고 차감 SQL에 도달하지 못한다 —
            // 애플리케이션에서는 같은 트랜잭션이 통째로 롤백되는 자리다.
            insertOrder(userId, stockId, key)
            jdbcTemplate.update(
                "UPDATE paper_accounts SET cash = cash - ? WHERE user_id = ? AND cash >= ?",
                debit, userId, debit,
            )
        }

        val cash = jdbcTemplate.queryForObject(
            "SELECT cash FROM paper_accounts WHERE user_id = ?", BigDecimal::class.java, userId,
        )!!
        assertThat(cash).isEqualByComparingTo(initialCash.subtract(debit))
    }
}
