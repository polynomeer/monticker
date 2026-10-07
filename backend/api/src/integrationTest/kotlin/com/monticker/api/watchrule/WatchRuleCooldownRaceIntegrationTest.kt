package com.monticker.api.watchrule

import com.monticker.api.matching.submit.MarketOrderResult
import com.monticker.api.matching.submit.OrderSubmitter
import com.monticker.api.quant.application.StrategySignalAccess
import com.monticker.api.support.PostgresIntegrationTest
import com.monticker.api.watchrule.application.FiringClaim
import com.monticker.api.watchrule.application.WatchRuleExecutor
import com.monticker.api.watchrule.application.WatchRuleGuards
import com.monticker.api.watchrule.domain.WatchRule
import com.monticker.api.watchrule.domain.WatchRuleExecution
import com.monticker.api.watchrule.domain.WatchRuleExecutionStatus
import com.monticker.api.watchrule.domain.WatchRuleSide
import com.monticker.api.watchrule.events.StockEventDetectedEvent
import com.monticker.api.watchrule.infrastructure.WatchRuleExecutionRepository
import com.monticker.api.watchrule.infrastructure.WatchRuleRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 보안 리뷰 2026-10 — watch rule 쿨다운·하루 한도의 원자성(ADR-077 Note, V80).
 *
 * 예전 쿨다운은 "최근 EXECUTED 기록이 있는가"를 조회한 뒤 주문했다. 기록은 체결 뒤에야 생기므로 같은 규칙에
 * **서로 다른** 이벤트가 동시에 오면 모두 조회를 통과해 여러 번 체결됐다((룰, 이벤트) 유니크와 멱등 키는 이벤트가
 * 달라 막지 못한다). MockK 단위 테스트는 호출을 순차 실행하므로 이 레이스를 원리적으로 재현하지 못한다 —
 * 여기서는 실제 Postgres와 동시 스레드로 친다.
 *
 * 불변조건: **쿨다운 안의 동시 평가 N개 중 주문은 정확히 하나.**
 */
class WatchRuleCooldownRaceIntegrationTest : PostgresIntegrationTest() {

    private val guards = WatchRuleGuards(jdbcTemplate, TransactionTemplate(DataSourceTransactionManager(dataSource)))

    private fun createUser(tag: String): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
            Long::class.java, "$tag-${System.nanoTime()}@test.local", tag,
        )!!

    private fun anyStockId(): Long =
        jdbcTemplate.queryForObject("SELECT id FROM stocks ORDER BY id LIMIT 1", Long::class.java)!!

    private fun createRule(userId: Long, stockId: Long, cooldownSec: Int, dailyLimit: Int? = null): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity, cooldown_sec, daily_limit)
            VALUES (?, ?, 'VOLUME_SURGE', 'BUY', 10, ?, ?) RETURNING id
            """.trimIndent(),
            Long::class.java, userId, stockId, cooldownSec, dailyLimit,
        )!!

    private fun todayCount(ruleId: Long): Int = guards.todayCounts(listOf(ruleId))[ruleId] ?: 0

    private fun lastFiredAt(ruleId: Long): Instant? =
        jdbcTemplate.queryForObject("SELECT last_fired_at FROM watch_rules WHERE id = ?", Timestamp::class.java, ruleId)?.toInstant()

    /** N개 스레드가 같은 출발선에서 동시에 [action]을 실행하고 결과를 모은다. */
    private fun <T> race(threads: Int, action: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<T>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val done = CountDownLatch(threads)
        repeat(threads) { i ->
            pool.submit {
                try {
                    start.await()
                    results += action(i)
                } catch (t: Throwable) {
                    errors += t
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()
        assertThat(errors).isEmpty()
        return results
    }

    @Test
    fun `ten concurrent claims inside a cooldown yield exactly one firing`() {
        val ruleId = createRule(createUser("wr-cd-claim"), anyStockId(), cooldownSec = 600)

        val claims = race(10) { guards.claimFiring(ruleId) }

        assertThat(claims.filterIsInstance<FiringClaim.Claimed>()).hasSize(1)
        assertThat(claims.filterIsInstance<FiringClaim.InCooldown>()).hasSize(9)
        assertThat(todayCount(ruleId)).isEqualTo(1)
        assertThat(lastFiredAt(ruleId)).isEqualTo(claims.filterIsInstance<FiringClaim.Claimed>().single().firedAt)
    }

    // 쿨다운 0이면 쿨다운은 막지 않는다 — 하루 한도만 원자적으로 지켜진다(V69 UPSERT가 같은 트랜잭션 안에서).
    @Test
    fun `with no cooldown concurrent claims stop exactly at the daily limit`() {
        val ruleId = createRule(createUser("wr-cd-daily"), anyStockId(), cooldownSec = 0, dailyLimit = 3)

        val claims = race(10) { guards.claimFiring(ruleId) }

        assertThat(claims.filterIsInstance<FiringClaim.Claimed>()).hasSize(3)
        assertThat(claims.filterIsInstance<FiringClaim.DailyLimitReached>()).hasSize(7)
        assertThat(todayCount(ruleId)).isEqualTo(3)
    }

    @Test
    fun `a released claim restores the previous cooldown and slot so the next event can fire`() {
        val ruleId = createRule(createUser("wr-cd-release"), anyStockId(), cooldownSec = 600)
        val before = Instant.now().minusSeconds(3600)
        jdbcTemplate.update("UPDATE watch_rules SET last_fired_at = ? WHERE id = ?", Timestamp.from(before), ruleId)

        val first = guards.claimFiring(ruleId) as FiringClaim.Claimed
        assertThat(guards.claimFiring(ruleId)).isInstanceOf(FiringClaim.InCooldown::class.java)

        guards.releaseFiring(first)

        assertThat(lastFiredAt(ruleId)).isEqualTo(first.previousFiredAt)
        assertThat(todayCount(ruleId)).isEqualTo(0)
        assertThat(guards.claimFiring(ruleId)).isInstanceOf(FiringClaim.Claimed::class.java)
    }

    @Test
    fun `an expired cooldown can be claimed and an inactive rule cannot`() {
        val userId = createUser("wr-cd-expired")
        val ruleId = createRule(userId, anyStockId(), cooldownSec = 600)
        jdbcTemplate.update("UPDATE watch_rules SET last_fired_at = now() - interval '601 seconds' WHERE id = ?", ruleId)
        assertThat(guards.claimFiring(ruleId)).isInstanceOf(FiringClaim.Claimed::class.java)

        val inactive = createRule(userId, anyStockId(), cooldownSec = 600)
        jdbcTemplate.update("UPDATE watch_rules SET is_active = false WHERE id = ?", inactive)
        assertThat(guards.claimFiring(inactive)).isEqualTo(FiringClaim.Inactive)
    }

    /**
     * 끝에서 끝까지 — 실제 [WatchRuleExecutor]가 같은 규칙의 서로 다른 이벤트 10개를 동시에 처리한다.
     * 주문 제출은 일부러 느리게(50ms) 해서 "판정 → 체결 기록" 사이 창을 넓힌다. 예전 구현은 여기서 10번 주문했다.
     */
    @Test
    fun `ten distinct events for one rule processed concurrently submit exactly one order`() {
        val userId = createUser("wr-cd-e2e")
        val stockId = anyStockId()
        val ruleId = createRule(userId, stockId, cooldownSec = 600)
        val rule = WatchRule(id = ruleId, userId = userId, stockId = stockId, eventType = "VOLUME_SURGE",
            side = WatchRuleSide.BUY, quantity = 10, cooldownSec = 600)

        val ruleRepo = mockk<WatchRuleRepository> {
            every { findAllByStockIdAndEventTypeAndIsActiveTrue(stockId, "VOLUME_SURGE") } returns listOf(rule)
        }
        val recorded = Collections.synchronizedList(mutableListOf<WatchRuleExecution>())
        val execRepo = mockk<WatchRuleExecutionRepository> {
            every { existsByWatchRuleIdAndStockEventId(any(), any()) } returns false
            every { save(any<WatchRuleExecution>()) } answers { firstArg<WatchRuleExecution>().also { recorded += it } }
        }
        val orders = AtomicInteger()
        val submitter = object : OrderSubmitter {
            override fun submitMarket(userId: Long, stockId: Long, side: String, quantity: Int, idempotencyKey: String?): MarketOrderResult {
                val id = orders.incrementAndGet().toLong()
                Thread.sleep(50)
                return MarketOrderResult(id, id, stockId, side, quantity, BigDecimal("1000"), BigDecimal("10000"), Instant.now())
            }
            override fun submitLimit(userId: Long, stockId: Long, side: String, quantity: Int, limitPrice: BigDecimal) =
                throw UnsupportedOperationException()
        }
        val executor = WatchRuleExecutor(ruleRepo, execRepo, submitter, SimpleMeterRegistry(), guards, mockk<StrategySignalAccess>())
        val base = System.nanoTime()

        race(10) { i ->
            executor.onEvent(StockEventDetectedEvent(eventId = base + i, stockId = stockId, eventType = "VOLUME_SURGE",
                importanceScore = 90, eventTimeMillis = System.currentTimeMillis()))
        }

        assertThat(orders.get()).isEqualTo(1)
        assertThat(recorded.count { it.status == WatchRuleExecutionStatus.EXECUTED }).isEqualTo(1)
        assertThat(recorded.filter { it.status == WatchRuleExecutionStatus.SKIPPED }).hasSize(9)
            .allSatisfy { assertThat(it.reason).contains("쿨다운") }
        assertThat(todayCount(ruleId)).isEqualTo(1)
    }
}
