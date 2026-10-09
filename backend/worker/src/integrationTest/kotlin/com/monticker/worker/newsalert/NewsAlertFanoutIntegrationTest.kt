package com.monticker.worker.newsalert

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.worker.search.SearchIndexEvent
import com.monticker.worker.support.PostgresIntegrationTest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-100 — 관심종목 뉴스·공시 팬아웃을 실제 Postgres(api 마이그레이션 전부)에서 본다:
 * 받는 사람은 그 종목을 **자기** 관심종목에 넣은 사용자뿐이고, 재전송·동시 리스너에도 사용자당 한 행·한 알림이며,
 * 시간당 상한과 신선도가 행·알림 수를 묶는다.
 */
class NewsAlertFanoutIntegrationTest : PostgresIntegrationTest() {

    private class CapturingPublisher : ApplicationEventPublisher {
        val events = CopyOnWriteArrayList<Any>()
        override fun publishEvent(event: Any) { events += event }
    }

    private val publisher = CapturingPublisher()
    private val fanout by lazy {
        NewsAlertFanout(
            jdbcTemplate, publisher, TransactionTemplate(DataSourceTransactionManager(dataSource)), ObjectMapper(), SimpleMeterRegistry(),
        )
    }
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private fun newUser(deleted: Boolean = false): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname, deleted_at) VALUES (?, 'news', ?) RETURNING id",
        Long::class.java, "news-${seq.incrementAndGet()}-${System.nanoTime()}@test.local", if (deleted) Timestamp.from(now) else null,
    )!!

    private fun newStock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, '뉴스테스트', 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "N%05d".format(seq.incrementAndGet() % 100_000),
    )!!

    private fun watch(userId: Long, vararg stockIds: Long, groups: Int = 1) {
        repeat(groups) { g ->
            val groupId = jdbcTemplate.queryForObject(
                "INSERT INTO watchlist_groups (user_id, name) VALUES (?, ?) RETURNING id", Long::class.java, userId, "g$g",
            )!!
            stockIds.forEach { jdbcTemplate.update("INSERT INTO watchlist_items (group_id, stock_id) VALUES (?, ?)", groupId, it) }
        }
    }

    private fun article(stockId: Long, id: Long = seq.incrementAndGet(), publishedAt: Instant = now.minus(10, ChronoUnit.MINUTES)) =
        NewsAlertCandidateEvent(
            kind = NewsAlertKind.NEWS, sourceId = id, stockId = stockId, title = "실적 발표",
            publishedAtMillis = publishedAt.toEpochMilli(), url = "https://news.test/$id", source = "테스트",
        )

    private fun rows(userId: Long): List<Map<String, Any?>> = jdbcTemplate.queryForList(
        "SELECT dedup_key, delivery_status, category, rule_id FROM alert_histories WHERE user_id = ? ORDER BY id", userId,
    )

    private fun notifies() = publisher.events.filterIsInstance<NewsAlertNotifyEvent>()

    private fun seedNewsRows(userId: Long, count: Int, status: String, at: Instant) = repeat(count) {
        jdbcTemplate.update(
            """INSERT INTO alert_histories (user_id, category, dedup_key, triggered_at, message, delivery_status)
               VALUES (?, 'NEWS', ?, ?, 'seed', ?)""",
            userId, "seed:${seq.incrementAndGet()}", Timestamp.from(at), status,
        )
    }

    @Test
    fun `only users who put the stock in their own watchlist get a row and a notification`() {
        val stock = newStock()
        val other = newStock()
        val watcher = newUser().also { watch(it, stock, groups = 2) }   // 두 그룹에 같은 종목 — 그래도 한 번
        val otherWatcher = newUser().also { watch(it, other) }
        val deleted = newUser(deleted = true).also { watch(it, stock) }
        val optedOut = newUser().also {
            watch(it, stock)
            jdbcTemplate.update("INSERT INTO notification_preferences (user_id, news_alert_push, news_alert_email) VALUES (?, false, false)", it)
        }
        val emailOnly = newUser().also {
            watch(it, stock)
            jdbcTemplate.update("INSERT INTO notification_preferences (user_id, news_alert_push, news_alert_email) VALUES (?, false, true)", it)
        }
        val event = article(stock)

        val inserted = fanout.fanOut(event, now)

        assertThat(inserted).isEqualTo(2)
        assertThat(rows(watcher)).singleElement().satisfies({ r ->
            assertThat(r["dedup_key"]).isEqualTo(event.historyKey())
            assertThat(r["category"]).isEqualTo("NEWS")
            assertThat(r["delivery_status"]).isEqualTo("QUEUED")
            assertThat(r["rule_id"]).isNull()
        })
        assertThat(rows(emailOnly)).hasSize(1)
        assertThat(rows(otherWatcher)).isEmpty()
        assertThat(rows(deleted)).isEmpty()
        assertThat(rows(optedOut)).isEmpty()
        assertThat(notifies().map { it.userId }).containsExactlyInAnyOrder(watcher, emailOnly)
        assertThat(notifies().map { it.toMessage().category }).containsOnly("NEWS")
        assertThat(publisher.events.filterIsInstance<SearchIndexEvent>().map { it.payload!!["userId"] })
            .containsExactlyInAnyOrder(watcher, emailOnly)
    }

    @Test
    fun `with the ops switch off nothing is recorded or notified`() {
        val stock = newStock()
        val watcher = newUser().also { watch(it, stock) }
        val switchedOff = NewsAlertFanout(
            jdbcTemplate, publisher, TransactionTemplate(DataSourceTransactionManager(dataSource)), ObjectMapper(), SimpleMeterRegistry(),
            enabled = false,
        )

        assertThat(switchedOff.fanOut(article(stock), now)).isZero()

        assertThat(rows(watcher)).isEmpty()
        assertThat(publisher.events).isEmpty()
    }

    @Test
    fun `redelivery of the same article writes nothing and notifies nobody again`() {
        val stock = newStock()
        val watcher = newUser().also { watch(it, stock) }
        val event = article(stock)

        assertThat(fanout.fanOut(event, now)).isEqualTo(1)
        val before = publisher.events.size
        assertThat(fanout.fanOut(event, now.plusSeconds(60))).isEqualTo(0)

        assertThat(rows(watcher)).hasSize(1)
        assertThat(publisher.events).hasSize(before)
    }

    @Test
    fun `ten concurrent redeliveries still give one row and one notification per user`() {
        val stock = newStock()
        val users = (1..3).map { newUser().also { u -> watch(u, stock) } }
        val event = article(stock)
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)
        val results = (1..10).map { pool.submit<Int> { start.await(); fanout.fanOut(event, now) } }
        start.countDown()
        val total = results.sumOf { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertThat(total).isEqualTo(3)
        users.forEach { assertThat(rows(it)).hasSize(1) }
        assertThat(notifies().map { it.userId }).containsExactlyInAnyOrderElementsOf(users)
    }

    @Test
    fun `a new watcher added before redelivery gets the article, earlier recipients do not get it twice`() {
        val stock = newStock()
        val first = newUser().also { watch(it, stock) }
        val event = article(stock)
        fanout.fanOut(event, now)
        val late = newUser().also { watch(it, stock) }

        assertThat(fanout.fanOut(event, now)).isEqualTo(1)
        assertThat(notifies().map { it.userId }).containsExactly(first, late)
    }

    @Test
    fun `articles older than 6 hours are not notified at all`() {
        val stock = newStock()
        val watcher = newUser().also { watch(it, stock) }

        assertThat(fanout.fanOut(article(stock, publishedAt = now.minus(7, ChronoUnit.HOURS)), now)).isEqualTo(0)
        assertThat(rows(watcher)).isEmpty()
        assertThat(publisher.events).isEmpty()
    }

    @Test
    fun `after 5 notifications in the last hour the next article is history only (CAPPED), no notification`() {
        val stock = newStock()
        val watcher = newUser().also { watch(it, stock) }
        seedNewsRows(watcher, 5, "QUEUED", now.minus(30, ChronoUnit.MINUTES))
        seedNewsRows(watcher, 10, "QUEUED", now.minus(2, ChronoUnit.HOURS))   // 창 밖 — 세지 않는다

        val event = article(stock)
        assertThat(fanout.fanOut(event, now)).isEqualTo(1)

        assertThat(rows(watcher).last()["delivery_status"]).isEqualTo("CAPPED")
        assertThat(notifies()).isEmpty()
        assertThat(publisher.events.filterIsInstance<SearchIndexEvent>().single().payload!!["deliveryStatus"]).isEqualTo("CAPPED")
    }

    @Test
    fun `after 20 news rows in the last hour further news is dropped, but an important disclosure is still recorded`() {
        val stock = newStock()
        val watcher = newUser().also { watch(it, stock) }
        seedNewsRows(watcher, 20, "CAPPED", now.minus(10, ChronoUnit.MINUTES))

        assertThat(fanout.fanOut(article(stock), now)).isEqualTo(0)
        val disclosure = NewsAlertCandidateEvent(
            kind = NewsAlertKind.DISCLOSURE, sourceId = seq.incrementAndGet(), stockId = stock, title = "[공시] 합병 결정",
            publishedAtMillis = now.toEpochMilli(), importanceScore = 90,
        )
        assertThat(fanout.fanOut(disclosure, now)).isEqualTo(1)

        assertThat(rows(watcher)).hasSize(21)
        assertThat(rows(watcher).last()["dedup_key"]).isEqualTo(disclosure.historyKey())
        assertThat(notifies().single().title).contains("공시")
    }

    @Test
    fun `a low-importance disclosure is ignored`() {
        val stock = newStock()
        val watcher = newUser().also { watch(it, stock) }
        val minor = NewsAlertCandidateEvent(
            kind = NewsAlertKind.DISCLOSURE, sourceId = seq.incrementAndGet(), stockId = stock, title = "[공시] 임원 변동",
            publishedAtMillis = now.toEpochMilli(), importanceScore = 60,
        )

        assertThat(fanout.fanOut(minor, now)).isEqualTo(0)
        assertThat(rows(watcher)).isEmpty()
    }

    companion object {
        private val seq = AtomicLong(System.nanoTime() % 1_000_000)
    }
}
