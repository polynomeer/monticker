package com.monticker.api.watchrule

import com.monticker.api.matching.application.LimitOrderFiller
import com.monticker.api.support.PostgresIntegrationTest
import com.monticker.api.watchlist.infrastructure.WatchlistOrderRepository
import com.monticker.api.watchrule.application.FiringClaim
import com.monticker.api.watchrule.application.WatchRuleGuards
import com.monticker.api.watchrule.application.WatchRuleOrderOutcomes
import com.monticker.api.watchrule.domain.WatchRuleOrderType
import com.monticker.api.watchrule.domain.WatchRuleSizeType
import com.monticker.api.watchrule.domain.WatchRuleTargetType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-098 — 지정가 발동(PLACED)의 이후 결과(V94)와 관심종목 그룹 삭제 경로(ADR-095 트리거).
 *
 * 체결·취소 쪽은 실제 매칭 코드와 같은 잠금을 흉내 낸다: 스위퍼는 [LimitOrderFiller.LOCK_SQL](`FOR UPDATE SKIP LOCKED`),
 * 사용자 취소는 `FOR UPDATE`. 리스너는 그 트랜잭션 안에서 [WatchRuleOrderOutcomes]를 부른다(동기 @EventListener와 같은 위치).
 * 스프링 컨텍스트 없이 같은 SQL을 실제 Postgres에서 동시에 돌린다. JVM 시간대와 무관해야 한다(`-Duser.timezone=UTC`로도 돈다).
 */
class WatchRuleLimitOutcomeIntegrationTest : PostgresIntegrationTest() {

    private val tx by lazy { TransactionTemplate(DataSourceTransactionManager(dataSource)) }
    private val outcomes by lazy { WatchRuleOrderOutcomes(jdbcTemplate, tx, SimpleMeterRegistry()) }

    private fun createUser(tag: String): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id", Long::class.java, "$tag-${System.nanoTime()}@test.local", tag,
    )!!

    private fun createStock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, 'lo', 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "L${System.nanoTime() % 1_000_000_000}",
    )!!

    private fun createRule(userId: Long, stockId: Long): Long = jdbcTemplate.queryForObject(
        """
        INSERT INTO watch_rules (user_id, stock_id, event_type, side, quantity, order_type, limit_offset_bps)
        VALUES (?, ?, 'VOLUME_SURGE', 'BUY', 3, 'LIMIT', -100) RETURNING id
        """.trimIndent(), Long::class.java, userId, stockId,
    )!!

    private fun pendingLimit(userId: Long, stockId: Long, ruleId: Long): Long = jdbcTemplate.queryForObject(
        """
        INSERT INTO orders (user_id, stock_id, side, order_type, quantity, limit_price, filled_qty, status, origin, origin_ref)
        VALUES (?, ?, 'BUY', 'LIMIT', 3, 990, 0, 'PENDING', 'WATCH_RULE', ?) RETURNING id
        """.trimIndent(), Long::class.java, userId, stockId, ruleId,
    )!!

    private fun placed(ruleId: Long, userId: Long, stockId: Long, orderId: Long): Long = jdbcTemplate.queryForObject(
        """
        INSERT INTO watch_rule_executions (watch_rule_id, user_id, stock_event_id, status, order_id, quantity, stock_id, limit_price, reason)
        VALUES (?, ?, ?, 'PLACED', ?, 3, ?, 990, '지정가 990 접수 — 미체결') RETURNING id
        """.trimIndent(), Long::class.java, ruleId, userId, System.nanoTime(), orderId, stockId,
    )!!

    private data class Exec(val status: String, val fillPrice: BigDecimal?, val resolvedAt: Instant?, val reason: String?)

    private fun exec(id: Long): Exec = jdbcTemplate.query(
        "SELECT status, fill_price, resolved_at, reason FROM watch_rule_executions WHERE id = ?",
        { rs, _ -> Exec(rs.getString("status"), rs.getBigDecimal("fill_price"), rs.getTimestamp("resolved_at")?.toInstant(), rs.getString("reason")) },
        id,
    ).single()

    private fun orderStatus(orderId: Long): String =
        jdbcTemplate.query("SELECT status FROM orders WHERE id = ?", { rs, _ -> rs.getString(1) }, orderId).single()

    /** 스위퍼 체결 한 건 — LimitOrderFiller와 같은 SKIP LOCKED 잠금, 같은 트랜잭션에서 리스너. 체결했으면 true. */
    private fun sweeperFill(orderId: Long, price: BigDecimal, hold: () -> Unit = {}): Boolean = tx.execute {
        jdbcTemplate.query(LimitOrderFiller.LOCK_SQL, { rs, _ -> rs.getLong(1) }, orderId).firstOrNull() ?: return@execute false
        jdbcTemplate.update(
            "UPDATE orders SET status = 'FILLED', filled_qty = quantity, avg_fill_price = ?, updated_at = now() WHERE id = ?", price, orderId,
        )
        outcomes.onFilled(orderId, price, Instant.now())
        hold()
        true
    }!!

    /** 사용자 취소 — MatchingService.cancelOrder와 같은 FOR UPDATE, 같은 트랜잭션에서 리스너. 취소했으면 true. */
    private fun userCancel(orderId: Long): Boolean = tx.execute {
        val status = jdbcTemplate.query("SELECT status FROM orders WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) }, orderId).single()
        if (status != "PENDING") return@execute false
        jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED', updated_at = now() WHERE id = ?", orderId)
        outcomes.onCancelled(orderId, "사용자 취소", Instant.now())
        true
    }!!

    @Test
    fun `concurrent sweeper fill and user cancel resolve each PLACED record exactly once`() {
        val userId = createUser("wr-lo-race")
        val stockId = createStock()
        val ruleId = createRule(userId, stockId)
        val cases = List(20) {
            val orderId = pendingLimit(userId, stockId, ruleId)
            orderId to placed(ruleId, userId, stockId, orderId)
        }

        val pool = Executors.newFixedThreadPool(8)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val results = Collections.synchronizedMap(mutableMapOf<Long, Pair<Boolean, Boolean>>())
        for ((orderId, _) in cases) {
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            var filled = false
            var cancelled = false
            pool.submit { try { start.await(); filled = sweeperFill(orderId, BigDecimal("985")) } catch (t: Throwable) { errors += t } finally { done.countDown() } }
            pool.submit { try { start.await(); cancelled = userCancel(orderId) } catch (t: Throwable) { errors += t } finally { done.countDown() } }
            start.countDown()
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
            results[orderId] = filled to cancelled
        }
        pool.shutdown()
        assertThat(errors).isEmpty()

        for ((orderId, execId) in cases) {
            val (filled, cancelled) = results[orderId]!!
            assertThat(filled xor cancelled).describedAs("order $orderId: exactly one of fill/cancel").isTrue()
            val e = exec(execId)
            assertThat(e.resolvedAt).isNotNull()
            if (filled) {
                assertThat(orderStatus(orderId)).isEqualTo("FILLED")
                assertThat(e.status).isEqualTo("FILLED")
                assertThat(e.fillPrice).isEqualByComparingTo("985")
            } else {
                assertThat(orderStatus(orderId)).isEqualTo("CANCELLED")
                assertThat(e.status).isEqualTo("CANCELLED")
                assertThat(e.fillPrice).isNull()
                assertThat(e.reason).contains("사용자 취소")
            }
            // 재발행(아웃박스 재전송·대조 재실행)은 아무것도 바꾸지 않는다
            val before = exec(execId)
            assertThat(outcomes.onFilled(orderId, BigDecimal("1"), Instant.now())).isZero()
            assertThat(outcomes.onCancelled(orderId, "다시", Instant.now())).isZero()
            assertThat(outcomes.reconcile(orderId)).isZero()
            assertThat(exec(execId)).isEqualTo(before)
        }
    }

    /** 접수(사가 트랜잭션)는 커밋됐고 PLACED 기록은 아직 — 그 사이에 체결이 끝나면 리스너는 0행이다. 대조가 메운다. */
    @Test
    fun `a fill committed before the PLACED record is written is picked up by reconcile`() {
        val userId = createUser("wr-lo-early")
        val stockId = createStock()
        val ruleId = createRule(userId, stockId)
        val orderId = pendingLimit(userId, stockId, ruleId)

        assertThat(sweeperFill(orderId, BigDecimal("987.5"))).isTrue()   // 리스너는 바꿀 행이 없었다
        val execId = placed(ruleId, userId, stockId, orderId)
        assertThat(exec(execId).status).isEqualTo("PLACED")

        assertThat(outcomes.reconcile(orderId)).isEqualTo(1)
        val e = exec(execId)
        assertThat(e.status).isEqualTo("FILLED")
        assertThat(e.fillPrice).isEqualByComparingTo("987.5")
        assertThat(e.resolvedAt).isNotNull()
        assertThat(outcomes.reconcile(orderId)).isZero()
    }

    /**
     * 체결 트랜잭션이 리스너 UPDATE(0행)까지 마치고 커밋 전인 순간에 PLACED가 기록되고 대조가 돈다 — 대조는 주문 행 잠금에서
     * 체결 커밋을 기다렸다가 커밋된 FILLED를 보고 전이한다. 어느 쪽 순서든 결과는 한 번이다.
     */
    @Test
    fun `reconcile waits for an in-flight fill and then resolves the record once`() {
        val userId = createUser("wr-lo-inflight")
        val stockId = createStock()
        val ruleId = createRule(userId, stockId)
        val orderId = pendingLimit(userId, stockId, ruleId)

        val listenerRan = CountDownLatch(1)
        val releaseFill = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val fill = pool.submit<Boolean> {
            sweeperFill(orderId, BigDecimal("980")) { listenerRan.countDown(); releaseFill.await(30, TimeUnit.SECONDS) }
        }
        assertThat(listenerRan.await(30, TimeUnit.SECONDS)).isTrue()

        val execId = placed(ruleId, userId, stockId, orderId)   // 실행기의 기록(자동 커밋)
        val reconcileDone = AtomicBoolean(false)
        val reconcile = pool.submit<Int> { outcomes.reconcile(orderId).also { reconcileDone.set(true) } }
        Thread.sleep(300)
        assertThat(reconcileDone.get()).describedAs("reconcile must wait on the order row lock").isFalse()
        assertThat(exec(execId).status).isEqualTo("PLACED")

        releaseFill.countDown()
        assertThat(fill.get(30, TimeUnit.SECONDS)).isTrue()
        assertThat(reconcile.get(30, TimeUnit.SECONDS)).isEqualTo(1)
        pool.shutdown()

        val e = exec(execId)
        assertThat(e.status).isEqualTo("FILLED")
        assertThat(e.fillPrice).isEqualByComparingTo("980")
    }

    @Test
    fun `reconcile leaves a still-pending order alone and the later cancel moves it`() {
        val userId = createUser("wr-lo-pending")
        val stockId = createStock()
        val ruleId = createRule(userId, stockId)
        val orderId = pendingLimit(userId, stockId, ruleId)
        val execId = placed(ruleId, userId, stockId, orderId)

        assertThat(outcomes.reconcile(orderId)).isZero()
        assertThat(exec(execId).status).isEqualTo("PLACED")

        assertThat(userCancel(orderId)).isTrue()
        assertThat(exec(execId).status).isEqualTo("CANCELLED")
    }

    @Test
    fun `a cancel committed before the record is reconciled with the order's reject reason`() {
        val userId = createUser("wr-lo-risk")
        val stockId = createStock()
        val ruleId = createRule(userId, stockId)
        val orderId = pendingLimit(userId, stockId, ruleId)
        jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED', reject_reason = '체결 시점 리스크 한도 초과: DAILY_LOSS' WHERE id = ?", orderId)
        val execId = placed(ruleId, userId, stockId, orderId)

        assertThat(outcomes.reconcile(orderId)).isEqualTo(1)
        val e = exec(execId)
        assertThat(e.status).isEqualTo("CANCELLED")
        assertThat(e.reason).contains("리스크 한도")
        assertThat(e.resolvedAt).isNotNull()
    }

    @Test
    fun `V94 keeps outcome time and fill price consistent with the status`() {
        val userId = createUser("wr-lo-ck")
        val stockId = createStock()
        val ruleId = createRule(userId, stockId)
        fun insert(status: String, fill: BigDecimal?, resolved: Instant?) = jdbcTemplate.update(
            "INSERT INTO watch_rule_executions (watch_rule_id, user_id, stock_event_id, status, fill_price, resolved_at) VALUES (?, ?, ?, ?, ?, ?)",
            ruleId, userId, System.nanoTime(), status, fill, resolved?.let(Timestamp::from),
        )
        assertThatThrownBy { insert("FILLED", BigDecimal("1"), null) }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insert("FILLED", null, Instant.now()) }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insert("CANCELLED", null, null) }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insert("PLACED", null, Instant.now()) }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insert("EXPIRED", null, Instant.now()) }.isInstanceOf(DataIntegrityViolationException::class.java)
        insert("FILLED", BigDecimal("1"), Instant.now())
        insert("CANCELLED", null, Instant.now())
    }

    // ── ADR-095 — 그룹 삭제 API 경로(WatchlistOrderRepository.deleteGroup)가 V92 트리거를 거친다 ───────────

    private fun createGroup(userId: Long, vararg stockIds: Long): Long {
        val groupId = jdbcTemplate.queryForObject(
            "INSERT INTO watchlist_groups (user_id, name) VALUES (?, 'del') RETURNING id", Long::class.java, userId,
        )!!
        stockIds.forEachIndexed { i, s ->
            jdbcTemplate.update("INSERT INTO watchlist_items (group_id, stock_id, sort_order) VALUES (?, ?, ?)", groupId, s, i)
        }
        return groupId
    }

    private fun groupRule(userId: Long, groupId: Long): Long = jdbcTemplate.queryForObject(
        "INSERT INTO watch_rules (user_id, target_type, target_group_id, event_type, side, quantity) VALUES (?, 'GROUP', ?, 'VOLUME_SURGE', 'BUY', 1) RETURNING id",
        Long::class.java, userId, groupId,
    )!!

    private fun isActive(ruleId: Long): Boolean =
        jdbcTemplate.queryForObject("SELECT is_active FROM watch_rules WHERE id = ?", Boolean::class.java, ruleId)!!

    private fun groupExists(groupId: Long): Boolean =
        jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM watchlist_groups WHERE id = ?)", Boolean::class.java, groupId)!!

    @Test
    fun `deleting a group through the API path cascades its items and switches off the rules targeting it`() {
        val owner = createUser("wr-gd-own")
        val (s1, s2) = createStock() to createStock()
        val groupId = createGroup(owner, s1, s2)
        val keptGroup = createGroup(owner, s1)
        val target = groupRule(owner, groupId)
        val other = groupRule(owner, keptGroup)
        val repo = WatchlistOrderRepository(jdbcTemplate)

        val deletedItems = tx.execute { repo.deleteGroup(owner, groupId) }

        assertThat(deletedItems).hasSize(2)
        assertThat(groupExists(groupId)).isFalse()
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM watchlist_items WHERE group_id = ?", Int::class.java, groupId)).isZero()
        assertThat(isActive(target)).isFalse()    // 트리거 — 규칙은 남고 꺼진다
        assertThat(jdbcTemplate.queryForObject("SELECT target_group_id FROM watch_rules WHERE id = ?", Long::class.java, target)).isEqualTo(groupId)
        assertThat(isActive(other)).isTrue()
    }

    @Test
    fun `deleting someone else's group through the API path is the same miss as a missing group and changes nothing`() {
        val owner = createUser("wr-gd-owner")
        val intruder = createUser("wr-gd-intr")
        val groupId = createGroup(owner, createStock())
        val rule = groupRule(owner, groupId)
        val repo = WatchlistOrderRepository(jdbcTemplate)

        assertThat(tx.execute { repo.deleteGroup(intruder, groupId) }).isNull()
        assertThat(tx.execute { repo.deleteGroup(intruder, Long.MAX_VALUE) }).isNull()
        assertThat(groupExists(groupId)).isTrue()
        assertThat(isActive(rule)).isTrue()
    }

    // ── ADR-098 — 발동권은 잠근 행의 기준을 함께 돌려준다(PATCH로 기준이 바뀐 뒤의 발동) ───────────

    @Test
    fun `the firing claim carries the locked row's target, order type and size basis`() {
        val userId = createUser("wr-shape")
        val stockId = createStock()
        val ruleId = createRule(userId, stockId)
        jdbcTemplate.update(
            "UPDATE watch_rules SET size_type = 'EQUITY_PCT', quantity = NULL, equity_pct = 2.5, cooldown_sec = 0 WHERE id = ?", ruleId,
        )
        val guards = WatchRuleGuards(jdbcTemplate, tx)

        val claim = guards.claimFiring(ruleId) as FiringClaim.Claimed
        val shape = claim.shape!!
        assertThat(shape.targetType).isEqualTo(WatchRuleTargetType.STOCK)
        assertThat(shape.stockId).isEqualTo(stockId)
        assertThat(shape.targetGroupId).isNull()
        assertThat(shape.orderType).isEqualTo(WatchRuleOrderType.LIMIT)
        assertThat(shape.limitOffsetBps).isEqualTo(-100)
        assertThat(shape.sizeType).isEqualTo(WatchRuleSizeType.EQUITY_PCT)
        assertThat(shape.quantity).isNull()
        assertThat(shape.equityPct).isEqualByComparingTo("2.5")
    }
}
