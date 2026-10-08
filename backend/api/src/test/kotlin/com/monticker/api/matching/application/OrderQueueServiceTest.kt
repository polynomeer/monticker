package com.monticker.api.matching.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** ADR-096 — SQL 조각 행을 가격별 대기열·내 순번으로 조립하는 규칙. */
class OrderQueueServiceTest {

    private fun row(side: String, price: Int, start: Int, count: Int, qty: Long, myId: Long? = null) =
        QueueSliceRow(side, BigDecimal(price), myId != null, myId, start, count, qty)

    private val now = Instant.parse("2026-10-08T01:00:00Z")

    @Test
    fun `my position counts every earlier order at the price, mine and others, and others stay aggregated`() {
        // 70000 매수: 남 2건(5주) → 내 #11(3주) → 남 1건(4주) → 내 #12(2주)
        val snap = OrderQueueService.assemble(1L, listOf(
            row("BUY", 70000, 1, 2, 5),
            row("BUY", 70000, 3, 1, 3, myId = 11),
            row("BUY", 70000, 4, 1, 4),
            row("BUY", 70000, 5, 1, 2, myId = 12),
        ), now)

        val level = snap.bids.single()
        assertThat(level.orderCount).isEqualTo(5)
        assertThat(level.quantity).isEqualTo(14)
        assertThat(level.slices.map { it.mine }).containsExactly(false, true, false, true)
        assertThat(level.slices.filter { !it.mine }.map { it.orderId }).containsOnlyNulls()

        val (first, second) = snap.mine
        assertThat(first.orderId).isEqualTo(11)
        assertThat(first.position).isEqualTo(3)
        assertThat(first.aheadCount).isEqualTo(2)
        assertThat(first.aheadQuantity).isEqualTo(5)
        assertThat(second.orderId).isEqualTo(12)
        assertThat(second.position).isEqualTo(5)
        assertThat(second.aheadCount).isEqualTo(4)
        assertThat(second.aheadQuantity).isEqualTo(12)
        assertThat(second.levelOrderCount).isEqualTo(5)
    }

    @Test
    fun `bids are best-first descending and asks ascending`() {
        val snap = OrderQueueService.assemble(1L, listOf(
            row("BUY", 100, 1, 1, 1), row("BUY", 102, 1, 1, 1), row("BUY", 101, 1, 1, 1),
            row("SELL", 105, 1, 1, 1), row("SELL", 103, 1, 1, 1),
        ), now)

        assertThat(snap.bids.map { it.price.toInt() }).containsExactly(102, 101, 100)
        assertThat(snap.asks.map { it.price.toInt() }).containsExactly(103, 105)
    }

    @Test
    fun `levels beyond the cap are dropped unless they hold my order, and my list is never capped`() {
        val rows = (1..5).map { row("BUY", 100 - it, 1, 1, 1) } + row("BUY", 90, 1, 1, 7, myId = 99)
        val snap = OrderQueueService.assemble(1L, rows, now, maxLevels = 2)

        assertThat(snap.bids.map { it.price.toInt() }).containsExactly(99, 98, 90)
        assertThat(snap.mine.single().orderId).isEqualTo(99)
        assertThat(snap.mine.single().position).isEqualTo(1)
        assertThat(snap.mine.single().aheadCount).isZero()
    }

    @Test
    fun `an empty queue is an empty snapshot`() {
        val snap = OrderQueueService.assemble(1L, emptyList(), now)
        assertThat(snap.bids).isEmpty()
        assertThat(snap.asks).isEmpty()
        assertThat(snap.mine).isEmpty()
    }
}
