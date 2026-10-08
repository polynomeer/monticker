package com.monticker.api.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.alert.application.AlertService
import com.monticker.api.alert.application.QuantSignalAlertFanout
import com.monticker.api.alert.infrastructure.UserAlertHistoryRepository
import com.monticker.api.common.notification.UserNotificationCommand
import com.monticker.api.common.search.SearchIndexEvent
import com.monticker.api.quant.application.QuantSignalFeedService
import com.monticker.api.quant.application.StrategySignalAccess
import com.monticker.api.quant.domain.RuleSetDocument
import com.monticker.api.quant.events.QuantSignalEmittedEvent
import com.monticker.api.quant.infrastructure.RuleSetRepository
import com.monticker.api.support.PostgresIntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-090 — 퀀트 시그널 → 알림 이력 팬아웃을 실제 Postgres에서 확인한다.
 *
 * - 멱등: 아웃박스가 같은 신호를 재전달·동시 전달해도 사용자당 행 하나, 알림·색인 이벤트도 한 번.
 * - 가시성: 룰셋 주인과 그 마켓 전략의 구독자만 받는다(ADR-035). 남의 시그널 이력은 읽음 처리에서도 없는 것과 같다(H6).
 * - 퀀트랩 상단 "오늘 신호"는 KST 달력일 — JVM 타임존이 UTC여도 같은 답(JAVA_TOOL_OPTIONS=-Duser.timezone=UTC로도 돌린다).
 */
class QuantSignalAlertIntegrationTest : PostgresIntegrationTest() {

    private val tx by lazy { TransactionTemplate(DataSourceTransactionManager(dataSource)) }
    private val ruleSets = mockk<RuleSetRepository>()
    private val histories by lazy { UserAlertHistoryRepository(jdbcTemplate) }
    private val access by lazy { StrategySignalAccess(ruleSets, jdbcTemplate) }
    private val published: MutableList<Any> = Collections.synchronizedList(mutableListOf())
    private val events = mockk<ApplicationEventPublisher> { every { publishEvent(any<Any>()) } answers { published += firstArg<Any>() } }
    private val fanout by lazy { QuantSignalAlertFanout(access, histories, events, ObjectMapper()) }
    private val alerts by lazy {
        AlertService(mockk(), ObjectMapper(), jdbcTemplate, mockk(), mockk(), mockk(), mockk(), mockk())
    }

    private fun user(tag: String, deleted: Boolean = false): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname, deleted_at) VALUES (?, ?, ?) RETURNING id", Long::class.java,
        "$tag-${UUID.randomUUID()}@test.local", tag, if (deleted) Timestamp.from(Instant.now()) else null,
    )!!

    private fun stock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, '시그널테스트', 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "QS" + UUID.randomUUID().toString().take(8),
    )!!

    /** Mongo 룰셋 id 모양(24자) */
    private fun ruleSetId() = UUID.randomUUID().toString().replace("-", "").take(24)

    private fun ruleSet(owner: Long, name: String = "골든크로스"): String {
        val id = ruleSetId()
        every { ruleSets.findById(id) } returns Optional.of(RuleSetDocument(id = id, userId = owner, name = name))
        every { ruleSets.findAllByUserId(owner) } returns listOf(RuleSetDocument(id = id, userId = owner, name = name))
        return id
    }

    private fun list(ruleSetId: String, creator: Long): Long = jdbcTemplate.queryForObject(
        "INSERT INTO strategy_market (ruleset_id, user_id) VALUES (?, ?) RETURNING id", Long::class.java, ruleSetId, creator,
    )!!

    private fun subscribe(marketId: Long, userId: Long) {
        jdbcTemplate.update("INSERT INTO strategy_subscriptions (market_id, user_id) VALUES (?, ?)", marketId, userId)
    }

    private fun signal(ruleSetId: String, stockId: Long, at: Instant = Instant.now()): Long = jdbcTemplate.queryForObject(
        "INSERT INTO quant_signals (rule_set_id, stock_id, direction, signal_time) VALUES (?, ?, 'BUY', ?) RETURNING id",
        Long::class.java, ruleSetId, stockId, Timestamp.from(at),
    )!!

    private fun event(signalId: Long, ruleSetId: String, stockId: Long) = QuantSignalEmittedEvent(
        signalId = signalId, ruleSetId = ruleSetId, stockId = stockId, direction = "BUY",
        signalTime = Instant.now(), price = 71_500.0, evalDate = LocalDate.now(),
    )

    private fun historyOwners(signalId: Long): List<Long> = jdbcTemplate.queryForList(
        "SELECT user_id FROM alert_histories WHERE dedup_key = ? ORDER BY user_id", Long::class.java, "quant-signal:$signalId",
    )

    // ── 가시성 ──────────────────────────────────────────────────────

    @Test
    fun `only the owner and current subscribers of that strategy get the signal in their history`() {
        val owner = user("owner"); val sub = user("sub"); val stranger = user("stranger")
        val otherSub = user("other-sub"); val gone = user("gone", deleted = true); val left = user("left")
        val stockId = stock()
        val rs = ruleSet(owner)
        val market = list(rs, owner)
        subscribe(market, sub)
        subscribe(market, gone)
        subscribe(list(ruleSet(stranger, "다른 전략"), stranger), otherSub)   // 다른 전략의 구독자
        subscribe(market, left)
        jdbcTemplate.update("DELETE FROM strategy_subscriptions WHERE market_id = ? AND user_id = ?", market, left)   // 구독 해지
        val signalId = signal(rs, stockId)

        tx.execute { fanout.fanOut(event(signalId, rs, stockId)) }

        assertThat(historyOwners(signalId)).containsExactlyInAnyOrder(owner, sub)
        assertThat(published.filterIsInstance<UserNotificationCommand>().map { it.userId }).containsExactlyInAnyOrder(owner, sub)
        val row = jdbcTemplate.queryForMap(
            "SELECT rule_id, category, stock_id, delivery_status, read_at FROM alert_histories WHERE user_id = ? AND dedup_key = ?",
            sub, "quant-signal:$signalId",
        )
        assertThat(row["rule_id"]).isNull()
        assertThat(row["category"]).isEqualTo("QUANT_SIGNAL")
        assertThat((row["stock_id"] as Number).toLong()).isEqualTo(stockId)
        assertThat(row["delivery_status"]).isEqualTo("QUEUED")
        assertThat(row["read_at"]).isNull()
    }

    @Test
    fun `a private ruleset that is not listed reaches only its owner`() {
        val owner = user("private-owner")
        val stockId = stock()
        val rs = ruleSet(owner)
        val signalId = signal(rs, stockId)

        tx.execute { fanout.fanOut(event(signalId, rs, stockId)) }

        assertThat(historyOwners(signalId)).containsExactly(owner)
    }

    @Test
    fun `someone else's signal history is indistinguishable from a missing one`() {
        val owner = user("h6-owner"); val stranger = user("h6-stranger")
        val stockId = stock()
        val rs = ruleSet(owner)
        val signalId = signal(rs, stockId)
        tx.execute { fanout.fanOut(event(signalId, rs, stockId)) }
        val historyId = jdbcTemplate.queryForObject(
            "SELECT id FROM alert_histories WHERE user_id = ? AND dedup_key = ?", Long::class.java, owner, "quant-signal:$signalId",
        )!!

        assertThatThrownBy { alerts.markRead(stranger, historyId) }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { alerts.markRead(stranger, Long.MAX_VALUE) }.isInstanceOf(NoSuchElementException::class.java)
        assertThat(alerts.readStates(stranger, listOf(historyId))).isEmpty()
        assertThat(alerts.markAllRead(stranger, Instant.now())).isZero()

        // 주인은 자기 시그널 이력을 읽음 처리할 수 있고, 읽지 않음 집계에서도 빠진다
        assertThat(alerts.readStates(owner, listOf(historyId))).containsKey(historyId)
        alerts.markRead(owner, historyId)
        assertThat(alerts.readStates(owner, listOf(historyId))[historyId]).isNotNull()
    }

    // ── 멱등 ────────────────────────────────────────────────────────

    @Test
    fun `redelivering the same signal writes nothing and publishes nothing new`() {
        val owner = user("redeliver-owner"); val sub = user("redeliver-sub")
        val stockId = stock()
        val rs = ruleSet(owner)
        subscribe(list(rs, owner), sub)
        val signalId = signal(rs, stockId)
        val e = event(signalId, rs, stockId)

        val first = tx.execute { fanout.fanOut(e) }
        val firstPublished = published.size
        val second = tx.execute { fanout.fanOut(e) }

        assertThat(first).isEqualTo(2)
        assertThat(second).isZero()
        assertThat(published).hasSize(firstPublished)
        assertThat(published.filterIsInstance<SearchIndexEvent>()).hasSize(2)
        assertThat(historyOwners(signalId)).hasSize(2)
    }

    @Test
    fun `ten concurrent deliveries of one signal leave exactly one row and one notification per user`() {
        val owner = user("race-owner"); val sub = user("race-sub")
        val stockId = stock()
        val rs = ruleSet(owner)
        subscribe(list(rs, owner), sub)
        val signalId = signal(rs, stockId)
        val e = event(signalId, rs, stockId)

        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)
        val done = CountDownLatch(10)
        repeat(10) {
            pool.submit {
                start.await()
                // 동시 삽입에서 진 쪽은 유니크 인덱스에서 기다렸다가 DO NOTHING — 예외 없이 0을 돌려준다
                runCatching { tx.execute { fanout.fanOut(e) } }
                done.countDown()
            }
        }
        start.countDown()
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        pool.shutdown()

        assertThat(historyOwners(signalId)).containsExactlyInAnyOrder(owner, sub)
        val commands = published.filterIsInstance<UserNotificationCommand>().filter { it.dedupKey.startsWith("quant-signal:$signalId:") }
        assertThat(commands.map { it.userId }).containsExactlyInAnyOrder(owner, sub)
    }

    @Test
    fun `the owner check rejects a row with no owner or with both owners`() {
        val u = user("chk")
        assertThatThrownBy {
            jdbcTemplate.update("INSERT INTO alert_histories (triggered_at, message) VALUES (now(), 'x')")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbcTemplate.update(
                "INSERT INTO alert_histories (user_id, triggered_at, message) VALUES (?, now(), 'x')", u,
            )   // category·dedup_key 없이 user_id만
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    // ── 퀀트랩 상단 집계 ─────────────────────────────────────────────

    @Test
    fun `today's signal count follows the KST day and only accessible strategies`() {
        val me = user("summary-me"); val other = user("summary-other")
        val stockId = stock()
        val mine = ruleSet(me, "내 전략")
        val subscribed = ruleSet(other, "구독 전략")
        val notSubscribed = ruleSetId()
        subscribe(list(subscribed, other), me)
        list(notSubscribed, other)
        every { ruleSets.findAllById(listOf(subscribed)) } returns listOf(RuleSetDocument(id = subscribed, userId = other, name = "구독 전략"))

        // KST 2026-10-08 00:30 = UTC 10-07 15:30
        val now = Instant.parse("2026-10-07T15:30:00Z")
        signal(mine, stockId, Instant.parse("2026-10-07T15:00:00Z"))          // KST 10-08 00:00 — 오늘(경계 포함)
        signal(subscribed, stockId, Instant.parse("2026-10-08T07:00:00Z"))    // KST 10-08 16:00 — 오늘
        signal(mine, stockId, Instant.parse("2026-10-07T14:59:59Z"))          // KST 10-07 23:59:59 — 어제(UTC로는 같은 10-07)
        signal(mine, stockId, Instant.parse("2026-10-08T15:00:00Z"))          // KST 10-09 00:00 — 내일
        signal(notSubscribed, stockId, Instant.parse("2026-10-07T16:00:00Z")) // 오늘이지만 볼 수 없는 전략

        val summary = QuantSignalFeedService(ruleSets, jdbcTemplate).summary(me, now)

        assertThat(summary.date).isEqualTo("2026-10-08")
        assertThat(summary.todaySignals).isEqualTo(2)
        assertThat(summary.activeSubscriptions).isEqualTo(1)
    }
}
