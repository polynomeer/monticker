package com.monticker.api.matching

import com.monticker.api.matching.application.LimitOrderSweeper
import com.monticker.api.matching.application.OrderQueueService
import com.monticker.api.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-096 — 모의 지정가 대기열 SQL(순번·조각·집계)을 실제 Postgres에서 검증한다.
 * 순번이 스위퍼의 실제 처리 순서와 같은지, 부분 체결 잔량·취소가 반영되는지, 동시 접수 중에도 스냅샷이 일관된지,
 * 남의 주문 id가 나오지 않는지. JVM 시간대와 무관해야 한다(`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`로도 돌린다).
 */
class OrderQueueIntegrationTest : PostgresIntegrationTest() {

    private val service = OrderQueueService(jdbcTemplate)

    private fun newUser(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO users (email, nickname) VALUES (?, ?) RETURNING id",
        Long::class.java, "oq-${System.nanoTime()}@test.local", "oq",
    )!!

    private fun stock(): Long = jdbcTemplate.queryForObject(
        "INSERT INTO stocks (symbol, name, market, exchange) VALUES (?, ?, 'KOSPI', 'KRX') RETURNING id",
        Long::class.java, "OQ${System.nanoTime() % 1_000_000}", "대기열",
    )!!

    private fun limit(
        userId: Long, stockId: Long, side: String, price: String, qty: Int,
        createdAt: Instant? = null, filled: Int = 0, status: String = if (filled > 0) "PARTIALLY_FILLED" else "PENDING",
    ): Long = jdbcTemplate.queryForObject(
        """INSERT INTO orders (user_id, stock_id, side, order_type, quantity, limit_price, filled_qty, status, created_at)
           VALUES (?, ?, ?, 'LIMIT', ?, ?, ?, ?, COALESCE(?, now())) RETURNING id""",
        Long::class.java, userId, stockId, side, qty, BigDecimal(price), filled, status, createdAt?.let(Timestamp::from),
    )!!

    @Test
    fun `my position follows (created_at, id), partial fills count their remainder, and others stay anonymous`() {
        val me = newUser(); val a = newUser(); val b = newUser()
        val s = stock()
        val t0 = Instant.parse("2026-10-08T00:00:00Z")
        limit(a, s, "BUY", "70000", 5, t0)
        val tieFirst = limit(b, s, "BUY", "70000", 4, t0.plusSeconds(1))
        val mine = limit(me, s, "BUY", "70000", 10, t0.plusSeconds(1), filled = 7) // 같은 시각 — id로 뒤
        limit(a, s, "BUY", "70000", 2, t0.plusSeconds(2))
        limit(a, s, "BUY", "69900", 1, t0)                                         // 다른 가격
        limit(a, s, "SELL", "70000", 3, t0)                                        // 다른 방향
        limit(a, s, "BUY", "70000", 9, t0.minusSeconds(5), status = "FILLED")      // 대기 아님
        limit(a, s, "BUY", "70000", 9, t0.minusSeconds(5), status = "CANCELLED")   // 대기 아님

        val snap = service.snapshot(me, s)

        val level = snap.bids.first { it.price.compareTo(BigDecimal("70000")) == 0 }
        assertThat(level.orderCount).isEqualTo(4)
        assertThat(level.quantity).isEqualTo(5 + 4 + 3 + 2L)
        assertThat(level.slices.map { it.mine to it.orderCount }).containsExactly(false to 2, true to 1, false to 1)
        assertThat(level.slices.filter { !it.mine }.map { it.orderId }).containsOnlyNulls()

        val pos = snap.mine.single()
        assertThat(pos.orderId).isEqualTo(mine)
        assertThat(tieFirst).isLessThan(mine)
        assertThat(pos.position).isEqualTo(3)
        assertThat(pos.aheadCount).isEqualTo(2)
        assertThat(pos.aheadQuantity).isEqualTo(9)
        assertThat(pos.remainingQuantity).isEqualTo(3)
        assertThat(snap.asks.single().orderCount).isEqualTo(1)

        // 다른 사용자가 보면 내 주문은 남의 조각으로 합쳐져 id가 나오지 않는다
        val theirs = service.snapshot(newUser(), s)
        assertThat(theirs.mine).isEmpty()
        assertThat(theirs.bids.flatMap { it.slices }.map { it.orderId }).containsOnlyNulls()
        assertThat(theirs.bids.first().slices.single().orderCount).isEqualTo(4)
    }

    @Test
    fun `cancelling an order ahead moves me up, and cancelling mine removes it from the queue`() {
        val me = newUser(); val other = newUser()
        val s = stock()
        val t0 = Instant.parse("2026-10-08T00:00:00Z")
        val ahead = limit(other, s, "SELL", "1000", 1, t0)
        val mine = limit(me, s, "SELL", "1000", 2, t0.plusSeconds(1))

        assertThat(service.snapshot(me, s).mine.single().position).isEqualTo(2)

        jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", ahead)
        assertThat(service.snapshot(me, s).mine.single().position).isEqualTo(1)

        jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", mine)
        val after = service.snapshot(me, s)
        assertThat(after.mine).isEmpty()
        assertThat(after.asks).isEmpty()
    }

    @Test
    fun `queue position equals the order the sweeper actually processes crossed orders in`() {
        val users = List(3) { newUser() }
        val s = stock()
        val t0 = Instant.parse("2026-10-08T00:00:00Z")
        // 접수 시각을 섞어 넣는다(일부는 같은 시각 — id로 끊는다)
        val ids = listOf(2L, 0L, 1L, 1L, 3L).mapIndexed { i, sec -> limit(users[i % 3], s, "BUY", "500", 1, t0.plusSeconds(sec)) }
        jdbcTemplate.update(
            "INSERT INTO candles_1m (stock_id, open, high, low, close, volume, candle_time) VALUES (?, 490, 490, 490, 490, 1, now())", s,
        )

        val sweepOrder = jdbcTemplate.query(LimitOrderSweeper.CANDIDATES_SQL.trimIndent(), { rs, _ -> rs.getLong("id") }, 10_000)
            .filter { it in ids }
        val byPosition = users.flatMap { service.snapshot(it, s).mine }.sortedBy { it.position }.map { it.orderId }

        assertThat(byPosition).hasSize(ids.size)
        assertThat(byPosition).isEqualTo(sweepOrder)
    }

    @Test
    fun `snapshots taken while orders are submitted and cancelled concurrently are always internally consistent`() {
        val s = stock()
        val users = List(8) { newUser() }
        val pool = Executors.newFixedThreadPool(9)
        val start = CountDownLatch(1)
        val writersDone = CountDownLatch(users.size)
        val stop = AtomicBoolean(false)
        val problems = ConcurrentLinkedQueue<String>()

        users.forEach { u ->
            pool.submit {
                try {
                    start.await()
                    repeat(15) { i ->
                        val id = limit(u, s, "BUY", "1000", 1 + i % 3)
                        if (i % 4 == 0) jdbcTemplate.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", id)
                    }
                } catch (e: Throwable) {
                    problems += "writer: ${e.message}"
                } finally {
                    writersDone.countDown()
                }
            }
        }
        pool.submit {
            start.await()
            while (!stop.get()) {
                val snap = service.snapshot(users[0], s)
                snap.bids.forEach { level ->
                    // 조각 순번은 1부터 빈틈없이 이어지고, 조각 합은 가격 합계와 같다
                    var expected = 1
                    level.slices.forEach { sl ->
                        if (sl.startPosition != expected) problems += "gap at ${sl.startPosition}, expected $expected"
                        expected += sl.orderCount
                    }
                    if (expected - 1 != level.orderCount) problems += "count mismatch"
                    if (level.slices.sumOf { it.quantity } != level.quantity) problems += "qty mismatch"
                }
                snap.mine.forEach { m -> if (m.aheadCount != m.position - 1) problems += "ahead mismatch" }
            }
        }
        start.countDown()
        assertThat(writersDone.await(30, TimeUnit.SECONDS)).isTrue()
        stop.set(true)
        pool.shutdown()
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        assertThat(problems).isEmpty()

        // 최종 상태: 각 사용자의 순번 = (created_at, id) 순위를 직접 센 값
        val expectedRank = jdbcTemplate.query(
            """SELECT id, row_number() OVER (ORDER BY created_at, id) AS rk FROM orders
               WHERE stock_id = ? AND status = 'PENDING'""",
            { rs, _ -> rs.getLong("id") to rs.getInt("rk") }, s,
        ).toMap()
        val actual = users.flatMap { service.snapshot(it, s).mine }.associate { it.orderId to it.position }
        assertThat(actual).isEqualTo(expectedRank)
        assertThat(expectedRank).hasSize(users.size * 11)
    }
}
