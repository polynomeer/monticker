package com.monticker.api.watchrule

import com.monticker.api.matching.submit.MarketOrderResult
import com.monticker.api.matching.submit.OrderOrigin
import com.monticker.api.matching.submit.OrderSubmitter
import com.monticker.api.quant.application.StrategySignalAccess
import com.monticker.api.support.PostgresIntegrationTest
import com.monticker.api.watchrule.application.WatchRuleExecutor
import com.monticker.api.watchrule.application.WatchRuleGuards
import com.monticker.api.watchrule.application.WatchRuleOrderPlanner
import com.monticker.api.watchrule.application.WatchRuleTargets
import com.monticker.api.watchrule.domain.WatchRule
import com.monticker.api.watchrule.domain.WatchRuleExecution
import com.monticker.api.watchrule.domain.WatchRuleExecutionStatus
import com.monticker.api.watchrule.domain.WatchRuleSide
import com.monticker.api.watchrule.domain.WatchRuleTargetType
import com.monticker.api.watchrule.events.StockEventDetectedEvent
import com.monticker.api.watchrule.infrastructure.WatchRuleExecutionRepository
import com.monticker.api.watchrule.infrastructure.WatchRuleQueries
import com.monticker.api.watchrule.infrastructure.WatchRuleRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-095 — 관심종목 그룹 대상 watch rule(V92).
 *
 * - V92 CHECK: 대상·주문 유형·수량 기준의 배타 조건과 범위
 * - 조회 SQL([WatchRuleQueries]): 평가 시점 그룹 구성, 그룹 소유자 = 규칙 소유자
 * - 그룹 삭제 → 규칙 중지(트리거), 연쇄 삭제 아님
 * - 그룹 안 **서로 다른 종목**의 이벤트가 동시에 와도 쿨다운(규칙 단위) 안에서 주문은 하나(ADR-077 claim)
 */
class WatchRuleGroupTargetIntegrationTest : PostgresIntegrationTest() {

    private val named by lazy { NamedParameterJdbcTemplate(jdbcTemplate) }
    private val guards by lazy { WatchRuleGuards(jdbcTemplate, TransactionTemplate(DataSourceTransactionManager(dataSource))) }

    private fun createUser(tag: String): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
            Long::class.java, "$tag-${System.nanoTime()}@test.local", tag,
        )!!

    private fun createStock(): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, 'grp', 'KOSPI', 'KRX') RETURNING id",
            Long::class.java, "G${System.nanoTime() % 1_000_000_000}",
        )!!

    private fun createGroup(userId: Long, vararg stockIds: Long): Long {
        val groupId = jdbcTemplate.queryForObject(
            "INSERT INTO watchlist_groups (user_id, name) VALUES (?, 'g') RETURNING id", Long::class.java, userId,
        )!!
        stockIds.forEachIndexed { i, s ->
            jdbcTemplate.update("INSERT INTO watchlist_items (group_id, stock_id, sort_order) VALUES (?, ?, ?)", groupId, s, i)
        }
        return groupId
    }

    private fun createGroupRule(userId: Long, groupId: Long, cooldownSec: Int = 600): Long =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO watch_rules (user_id, stock_id, target_type, target_group_id, event_type, side, quantity, cooldown_sec)
            VALUES (?, NULL, 'GROUP', ?, 'VOLUME_SURGE', 'BUY', 2, ?) RETURNING id
            """.trimIndent(),
            Long::class.java, userId, groupId, cooldownSec,
        )!!

    private fun createStockRule(userId: Long, stockId: Long): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity) VALUES (?, ?, 'VOLUME_SURGE', 'BUY', 1) RETURNING id",
            Long::class.java, userId, stockId,
        )!!

    /** 리포지토리 네이티브 쿼리와 같은 SQL을 실행해 규칙 id를 돌려준다. */
    private fun activeRuleIdsFor(stockId: Long): List<Long> =
        named.query(WatchRuleQueries.ACTIVE_FOR_EVENT, mapOf("stockId" to stockId, "eventType" to "VOLUME_SURGE")) { rs, _ -> rs.getLong("id") }

    /** 같은 SQL로 실행기에 넘길 엔티티를 만든다 — 스프링 컨텍스트 없이 [WatchRuleRepository]를 대신한다. */
    private fun activeRulesFor(stockId: Long, eventType: String): List<WatchRule> =
        named.query(WatchRuleQueries.ACTIVE_FOR_EVENT, mapOf("stockId" to stockId, "eventType" to eventType)) { rs, _ ->
            WatchRule(
                id = rs.getLong("id"), userId = rs.getLong("user_id"), stockId = (rs.getObject("stock_id") as Number?)?.toLong(),
                eventType = rs.getString("event_type"), side = WatchRuleSide.valueOf(rs.getString("side")),
                quantity = rs.getObject("quantity") as Int?, cooldownSec = rs.getInt("cooldown_sec"),
                targetType = WatchRuleTargetType.valueOf(rs.getString("target_type")),
                targetGroupId = (rs.getObject("target_group_id") as Number?)?.toLong(),
            )
        }

    @Test
    fun `V92 constraints keep target, order type and size mutually consistent`() {
        val userId = createUser("wr-g-ck")
        val stockId = createStock()
        val groupId = createGroup(userId, stockId)
        fun insert(sql: String, vararg args: Any?) = jdbcTemplate.update(sql, *args)

        // 그룹 규칙인데 종목도 있다
        assertThatThrownBy {
            insert("INSERT INTO watch_rules (user_id, stock_id, target_type, target_group_id, event_type, side, quantity) VALUES (?, ?, 'GROUP', ?, 'VOLUME_SURGE', 'BUY', 1)", userId, stockId, groupId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        // 종목 규칙인데 종목이 없다
        assertThatThrownBy {
            insert("INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity) VALUES (?, NULL, 'VOLUME_SURGE', 'BUY', 1)", userId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        // 지정가 오프셋 범위 밖, 시장가에 오프셋
        assertThatThrownBy {
            insert("INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity, order_type, limit_offset_bps) VALUES (?, ?, 'VOLUME_SURGE', 'BUY', 1, 'LIMIT', 1500)", userId, stockId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insert("INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity, limit_offset_bps) VALUES (?, ?, 'VOLUME_SURGE', 'BUY', 1, 10)", userId, stockId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        // 계좌 % 범위 밖, 계좌 %인데 주 수도 있다
        assertThatThrownBy {
            insert("INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity, size_type, equity_pct) VALUES (?, ?, 'VOLUME_SURGE', 'BUY', NULL, 'EQUITY_PCT', 30)", userId, stockId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            insert("INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity, size_type, equity_pct) VALUES (?, ?, 'VOLUME_SURGE', 'BUY', 3, 'EQUITY_PCT', 5)", userId, stockId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        // 올바른 조합 + PLACED 발동 기록
        val ruleId = jdbcTemplate.queryForObject(
            """
            INSERT INTO watch_rules (user_id, target_type, target_group_id, event_type, side, quantity, order_type, limit_offset_bps, size_type, equity_pct)
            VALUES (?, 'GROUP', ?, 'VOLUME_SURGE', 'BUY', NULL, 'LIMIT', -100, 'EQUITY_PCT', 2.5) RETURNING id
            """.trimIndent(), Long::class.java, userId, groupId,
        )!!
        insert(
            "INSERT INTO watch_rule_executions (watch_rule_id, user_id, stock_event_id, status, stock_id, limit_price, quantity) VALUES (?, ?, ?, 'PLACED', ?, 990, 3)",
            ruleId, userId, System.nanoTime(), stockId,
        )
    }

    @Test
    fun `group rules match the stocks in the group at evaluation time and only the owner's group`() {
        val owner = createUser("wr-g-own")
        val intruder = createUser("wr-g-intr")
        val (s1, s2, s3) = Triple(createStock(), createStock(), createStock())
        val groupId = createGroup(owner, s1, s2)
        val groupRule = createGroupRule(owner, groupId)
        val stockRule = createStockRule(owner, s1)
        // 남의 그룹 id를 직접 넣은 규칙(서비스는 404로 막지만 DB에 있더라도) — 발동하지 않아야 한다.
        val forged = createGroupRule(intruder, groupId)

        assertThat(activeRuleIdsFor(s1)).containsExactlyInAnyOrder(groupRule, stockRule)
        assertThat(activeRuleIdsFor(s2)).containsExactly(groupRule)
        assertThat(activeRuleIdsFor(s3)).doesNotContain(groupRule, forged)
        assertThat(activeRuleIdsFor(s1)).doesNotContain(forged)

        // 그룹에서 빼면 다음 평가부터 대상이 아니다. 넣으면 대상이다.
        jdbcTemplate.update("DELETE FROM watchlist_items WHERE group_id = ? AND stock_id = ?", groupId, s2)
        jdbcTemplate.update("INSERT INTO watchlist_items (group_id, stock_id, sort_order) VALUES (?, ?, 5)", groupId, s3)
        assertThat(activeRuleIdsFor(s2)).doesNotContain(groupRule)
        assertThat(activeRuleIdsFor(s3)).containsExactly(groupRule)
    }

    @Test
    fun `deleting the target group disables the rule instead of deleting it`() {
        val owner = createUser("wr-g-del")
        val s1 = createStock()
        val groupId = createGroup(owner, s1)
        val ruleId = createGroupRule(owner, groupId)
        val other = createGroupRule(owner, createGroup(owner, s1))

        jdbcTemplate.update("DELETE FROM watchlist_groups WHERE id = ?", groupId)   // items는 FK CASCADE

        val row = jdbcTemplate.queryForMap("SELECT is_active, target_group_id FROM watch_rules WHERE id = ?", ruleId)
        assertThat(row["is_active"]).isEqualTo(false)
        assertThat((row["target_group_id"] as Number).toLong()).isEqualTo(groupId)
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM watch_rules WHERE id = ?", Boolean::class.java, other)).isTrue()
        assertThat(WatchRuleTargets(jdbcTemplate).ownsGroup(owner, groupId)).isFalse()
        assertThat(activeRuleIdsFor(s1)).containsExactly(other)
    }

    /**
     * ADR-095 + ADR-077 Note — 쿨다운은 규칙 단위다. 그룹 안 10개 **서로 다른 종목**에 동시에 이벤트가 와도
     * 행 잠금 claim이 하나만 통과시킨다. 실제 실행기 + 실제 Guards + V92 조회 SQL.
     */
    @Test
    fun `concurrent events on different stocks of one group fire once per cooldown`() {
        val owner = createUser("wr-g-race")
        val stocks = List(10) { createStock() }
        val groupId = createGroup(owner, *stocks.toLongArray())
        val ruleId = createGroupRule(owner, groupId, cooldownSec = 600)

        val ruleRepo = mockk<WatchRuleRepository> {
            every { findActiveForEvent(any(), any()) } answers { activeRulesFor(firstArg(), secondArg()) }
        }
        val recorded = Collections.synchronizedList(mutableListOf<WatchRuleExecution>())
        val execRepo = mockk<WatchRuleExecutionRepository> {
            every { existsByWatchRuleIdAndStockEventId(any(), any()) } returns false
            every { save(any<WatchRuleExecution>()) } answers { firstArg<WatchRuleExecution>().also { recorded += it } }
        }
        val orders = AtomicInteger()
        val orderedStocks = Collections.synchronizedList(mutableListOf<Long>())
        val submitter = object : OrderSubmitter {
            override fun submitMarket(userId: Long, stockId: Long, side: String, quantity: Int, origin: OrderOrigin, idempotencyKey: String?): MarketOrderResult {
                assertThat(origin).isEqualTo(OrderOrigin.watchRule(ruleId))
                val id = orders.incrementAndGet().toLong()
                orderedStocks += stockId
                Thread.sleep(50)
                return MarketOrderResult(id, id, stockId, side, quantity, BigDecimal("1000"), BigDecimal("2000"), Instant.now())
            }
            override fun submitLimit(userId: Long, stockId: Long, side: String, quantity: Int, limitPrice: BigDecimal, origin: OrderOrigin, idempotencyKey: String?) =
                throw UnsupportedOperationException()
        }
        val planner = WatchRuleOrderPlanner(WatchRuleTargets(jdbcTemplate), mockk())
        val executor = WatchRuleExecutor(ruleRepo, execRepo, submitter, SimpleMeterRegistry(), guards, mockk<StrategySignalAccess>(), planner)
        val base = System.nanoTime()

        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)
        val done = CountDownLatch(10)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        stocks.forEachIndexed { i, s ->
            pool.submit {
                try {
                    start.await()
                    executor.onEvent(StockEventDetectedEvent(eventId = base + i, stockId = s, eventType = "VOLUME_SURGE",
                        importanceScore = 90, eventTimeMillis = System.currentTimeMillis()))
                } catch (t: Throwable) { errors += t } finally { done.countDown() }
            }
        }
        start.countDown()
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()

        assertThat(errors).isEmpty()
        assertThat(orders.get()).isEqualTo(1)
        val executed = recorded.single { it.status == WatchRuleExecutionStatus.EXECUTED }
        assertThat(executed.stockId).isEqualTo(orderedStocks.single())
        assertThat(recorded.filter { it.status == WatchRuleExecutionStatus.SKIPPED }).hasSize(9)
            .allSatisfy { assertThat(it.reason).contains("쿨다운") }
        // 기록마다 그 이벤트의 종목이 남는다 — 10개 종목 모두 한 번씩.
        assertThat(recorded.map { it.stockId }).containsExactlyInAnyOrderElementsOf(stocks)
        assertThat(guards.todayCounts(listOf(ruleId))[ruleId]).isEqualTo(1)
    }
}
