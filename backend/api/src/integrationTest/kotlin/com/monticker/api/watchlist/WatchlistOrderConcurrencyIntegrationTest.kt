package com.monticker.api.watchlist

import com.monticker.api.support.PostgresIntegrationTest
import com.monticker.api.watchlist.infrastructure.WatchlistOrderRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 관심종목 순서 이동(design-rollout-plan §2 /watchlist) — 그룹 행 잠금 아래에서 순서를 0..n-1로 다시 매기므로
 * 동시에 옮기고·추가해도 순서 값이 겹치거나 빈칸이 생기지 않는다. 실제 Postgres에서 READ COMMITTED 동시 트랜잭션으로 확인한다.
 */
class WatchlistOrderConcurrencyIntegrationTest : PostgresIntegrationTest() {

    private val repo by lazy { WatchlistOrderRepository(jdbcTemplate) }
    private val tx by lazy { TransactionTemplate(DataSourceTransactionManager(dataSource)) }

    private fun user(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id", Long::class.java,
        "wl-${UUID.randomUUID()}@test.local", "wl",
    )!!

    private fun stock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, '순서테스트', 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "WL" + UUID.randomUUID().toString().take(8),
    )!!

    private fun group(userId: Long): Long = jdbcTemplate.queryForObject(
        "INSERT INTO watchlist_groups (user_id, name) VALUES (?, 'g') RETURNING id", Long::class.java, userId,
    )!!

    /** 예전 데이터처럼 sort_order를 전부 0으로 넣는다 — 첫 이동이 추가 순(id)으로 0..n-1을 매겨야 한다 */
    private fun items(groupId: Long, n: Int): List<Long> = (1..n).map {
        jdbcTemplate.queryForObject(
            "INSERT INTO watchlist_items (group_id, stock_id, sort_order) VALUES (?, ?, 0) RETURNING id",
            Long::class.java, groupId, stock(),
        )!!
    }

    private fun orders(groupId: Long): List<Pair<Long, Int>> = jdbcTemplate.query(
        "SELECT id, sort_order FROM watchlist_items WHERE group_id = ? ORDER BY sort_order, id",
        { rs, _ -> rs.getLong("id") to rs.getInt("sort_order") }, groupId,
    )

    @Test
    fun `move renumbers the whole group to 0 to n-1`() {
        val u = user()
        val g = group(u)
        val ids = items(g, 4)

        val placed = tx.execute { repo.moveItem(u, ids[3], 1) }

        assertThat(placed).isEqualTo(1)
        assertThat(orders(g)).containsExactly(ids[0] to 0, ids[3] to 1, ids[1] to 2, ids[2] to 3)
        // 범위를 넘으면 맨 끝
        assertThat(tx.execute { repo.moveItem(u, ids[0], 100) }).isEqualTo(3)
        assertThat(orders(g).map { it.first }).containsExactly(ids[3], ids[1], ids[2], ids[0])
    }

    @Test
    fun `someone else's item is indistinguishable from a missing one and nothing changes`() {
        val owner = user()
        val other = user()
        val g = group(owner)
        val ids = items(g, 3)
        val before = orders(g)

        assertThatThrownBy { tx.execute { repo.moveItem(other, ids[0], 2) } }
            .isInstanceOf(NoSuchElementException::class.java)
            .hasMessage("Watchlist item not found: ${ids[0]}")
        val missingId = ids.max() + 1_000_000
        assertThatThrownBy { tx.execute { repo.moveItem(other, missingId, 2) } }
            .isInstanceOf(NoSuchElementException::class.java)
            .hasMessage("Watchlist item not found: $missingId")
        assertThat(tx.execute { repo.lockGroup(other, g) }).isFalse()
        assertThat(orders(g)).isEqualTo(before)
    }

    @Test
    fun `concurrent moves never leave duplicate or gapped sort orders`() {
        val u = user()
        val g = group(u)
        val ids = items(g, 12)
        val threads = 8
        val perThread = 25
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(threads) { t ->
            pool.submit {
                val rnd = Random(t)
                start.await()
                repeat(perThread) {
                    try {
                        tx.execute { repo.moveItem(u, ids[rnd.nextInt(ids.size)], rnd.nextInt(ids.size + 2)) }
                    } catch (e: Throwable) {
                        errors += e
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue()

        assertThat(errors).isEmpty()
        val final = orders(g)
        assertThat(final.map { it.second }).containsExactlyElementsOf(0 until ids.size)
        assertThat(final.map { it.first }).containsExactlyInAnyOrderElementsOf(ids)
    }

    @Test
    fun `concurrent appends under the group lock get distinct consecutive positions`() {
        val u = user()
        val g = group(u)
        val stocks = (1..10).map { stock() }
        val pool = Executors.newFixedThreadPool(stocks.size)
        val start = CountDownLatch(1)
        stocks.forEach { s ->
            pool.submit {
                start.await()
                tx.execute {
                    check(repo.lockGroup(u, g))
                    val pos = repo.nextSortOrder(g)
                    jdbcTemplate.update("INSERT INTO watchlist_items (group_id, stock_id, sort_order) VALUES (?, ?, ?)", g, s, pos)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue()

        assertThat(orders(g).map { it.second }).containsExactlyElementsOf(0 until stocks.size)
    }

    @Test
    fun `compact closes the gap left by a delete`() {
        val u = user()
        val g = group(u)
        val ids = items(g, 4)
        tx.execute { repo.compact(g) }

        tx.execute {
            check(repo.lockGroupOfItem(u, ids[1]) == g)
            jdbcTemplate.update("DELETE FROM watchlist_items WHERE id = ?", ids[1])
            repo.compact(g)
        }

        assertThat(orders(g)).containsExactly(ids[0] to 0, ids[2] to 1, ids[3] to 2)
    }
}
