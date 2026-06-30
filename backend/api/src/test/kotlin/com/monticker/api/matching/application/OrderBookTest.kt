package com.monticker.api.matching.application

import com.monticker.api.matching.domain.Order
import com.monticker.api.matching.domain.OrderSide
import com.monticker.api.matching.domain.OrderType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OrderBookTest {

    private val book = OrderBook(stockId = 1L)

    private fun order(
        id: Long,
        side: OrderSide,
        price: BigDecimal,
        qty: Int = 10,
    ) = Order(
        id = id,
        userId = 1L,
        stockId = 1L,
        side = side,
        orderType = OrderType.LIMIT,
        quantity = qty,
        limitPrice = price,
    )

    @Test
    fun `addBid places order in bids TreeMap with highest price first`() {
        book.addBid(order(1L, OrderSide.BUY, BigDecimal("100")))
        book.addBid(order(2L, OrderSide.BUY, BigDecimal("105")))
        book.addBid(order(3L, OrderSide.BUY, BigDecimal("95")))

        assertThat(book.bids.firstKey()).isEqualByComparingTo(BigDecimal("105"))
        assertThat(book.bids.keys.toList()).containsExactly(
            BigDecimal("105"), BigDecimal("100"), BigDecimal("95")
        )
    }

    @Test
    fun `addAsk places order in asks TreeMap with lowest price first`() {
        book.addAsk(order(1L, OrderSide.SELL, BigDecimal("100")))
        book.addAsk(order(2L, OrderSide.SELL, BigDecimal("95")))
        book.addAsk(order(3L, OrderSide.SELL, BigDecimal("105")))

        assertThat(book.asks.firstKey()).isEqualByComparingTo(BigDecimal("95"))
        assertThat(book.asks.keys.toList()).containsExactly(
            BigDecimal("95"), BigDecimal("100"), BigDecimal("105")
        )
    }

    @Test
    fun `getBestBid returns top of book price`() {
        book.addBid(order(1L, OrderSide.BUY, BigDecimal("100")))
        book.addBid(order(2L, OrderSide.BUY, BigDecimal("105")))

        assertThat(book.getBestBid()).isEqualByComparingTo(BigDecimal("105"))
    }

    @Test
    fun `getBestAsk returns top of book price`() {
        book.addAsk(order(1L, OrderSide.SELL, BigDecimal("100")))
        book.addAsk(order(2L, OrderSide.SELL, BigDecimal("95")))

        assertThat(book.getBestAsk()).isEqualByComparingTo(BigDecimal("95"))
    }

    @Test
    fun `getBestBid returns null when bids side is empty`() {
        // Previously a real bug: firstKey() was evaluated before the isNotEmpty
        // guard, throwing NoSuchElementException instead of returning null.
        // Fixed in OrderBook.kt to check emptiness first.
        assertThat(book.getBestBid()).isNull()
    }

    @Test
    fun `getBestAsk returns null when asks side is empty`() {
        assertThat(book.getBestAsk()).isNull()
    }

    @Test
    fun `multiple orders at same price level maintain FIFO time priority`() {
        book.addBid(order(1L, OrderSide.BUY, BigDecimal("100")))
        book.addBid(order(2L, OrderSide.BUY, BigDecimal("100")))
        book.addBid(order(3L, OrderSide.BUY, BigDecimal("100")))

        val queue = book.bids[BigDecimal("100")]!!
        assertThat(queue.map { it.id }).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `removeOrder removes a specific order by id from bids`() {
        book.addBid(order(1L, OrderSide.BUY, BigDecimal("100")))
        book.addBid(order(2L, OrderSide.BUY, BigDecimal("100")))

        val removed = book.removeOrder(1L, OrderSide.BUY)

        assertThat(removed).isTrue()
        assertThat(book.bids[BigDecimal("100")]!!.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `removeOrder removes a specific order by id from asks`() {
        book.addAsk(order(1L, OrderSide.SELL, BigDecimal("100")))
        book.addAsk(order(2L, OrderSide.SELL, BigDecimal("100")))

        val removed = book.removeOrder(2L, OrderSide.SELL)

        assertThat(removed).isTrue()
        assertThat(book.asks[BigDecimal("100")]!!.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `removeOrder returns false when order id does not exist`() {
        book.addBid(order(1L, OrderSide.BUY, BigDecimal("100")))

        val removed = book.removeOrder(999L, OrderSide.BUY)

        assertThat(removed).isFalse()
        assertThat(book.bids[BigDecimal("100")]!!.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `removeOrder cleans up empty price level after last order removed`() {
        book.addBid(order(1L, OrderSide.BUY, BigDecimal("100")))

        val removed = book.removeOrder(1L, OrderSide.BUY)

        assertThat(removed).isTrue()
        assertThat(book.bids).doesNotContainKey(BigDecimal("100"))
        assertThat(book.bids).isEmpty()
    }

    @Test
    fun `removeOrder cleans up empty ask price level after last order removed`() {
        book.addAsk(order(1L, OrderSide.SELL, BigDecimal("100")))

        val removed = book.removeOrder(1L, OrderSide.SELL)

        assertThat(removed).isTrue()
        assertThat(book.asks).doesNotContainKey(BigDecimal("100"))
        assertThat(book.asks).isEmpty()
    }
}
