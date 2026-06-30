package com.monticker.api.matching.application

import com.monticker.api.matching.domain.Order
import com.monticker.api.matching.domain.OrderSide
import com.monticker.api.matching.domain.OrderType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MatchingOrderBookServiceTest {

    private val service = MatchingOrderBookService()

    private fun order(
        id: Long,
        stockId: Long,
        side: OrderSide,
        orderType: OrderType,
        qty: Int,
        price: BigDecimal? = null,
    ) = Order(
        id = id,
        userId = 1L,
        stockId = stockId,
        side = side,
        orderType = orderType,
        quantity = qty,
        limitPrice = price,
    )

    @Test
    fun `market buy matches against best ask, full fill when ask qty greater or equal`() {
        val ask = order(1L, 100L, OrderSide.SELL, OrderType.LIMIT, qty = 20, price = BigDecimal("1000"))
        service.submit(ask)

        val marketBuy = order(2L, 100L, OrderSide.BUY, OrderType.MARKET, qty = 10)
        val results = service.submit(marketBuy)

        assertThat(results).hasSize(1)
        assertThat(results[0].makerOrderId).isEqualTo(1L)
        assertThat(results[0].takerOrderId).isEqualTo(2L)
        assertThat(results[0].price).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(results[0].qty).isEqualTo(10)
        assertThat(marketBuy.filledQty).isEqualTo(10)
        assertThat(marketBuy.remainingQty).isEqualTo(0)
        assertThat(ask.filledQty).isEqualTo(10)
    }

    @Test
    fun `market buy matches across multiple ask price levels when one level insufficient`() {
        val ask1 = order(1L, 100L, OrderSide.SELL, OrderType.LIMIT, qty = 5, price = BigDecimal("1000"))
        val ask2 = order(2L, 100L, OrderSide.SELL, OrderType.LIMIT, qty = 10, price = BigDecimal("1010"))
        service.submit(ask1)
        service.submit(ask2)

        val marketBuy = order(3L, 100L, OrderSide.BUY, OrderType.MARKET, qty = 12)
        val results = service.submit(marketBuy)

        assertThat(results).hasSize(2)
        assertThat(results[0].price).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(results[0].qty).isEqualTo(5)
        assertThat(results[1].price).isEqualByComparingTo(BigDecimal("1010"))
        assertThat(results[1].qty).isEqualTo(7)
        assertThat(marketBuy.filledQty).isEqualTo(12)
        assertThat(marketBuy.remainingQty).isEqualTo(0)
    }

    @Test
    fun `limit buy with price greater or equal to best ask fills immediately`() {
        val ask = order(1L, 100L, OrderSide.SELL, OrderType.LIMIT, qty = 10, price = BigDecimal("1000"))
        service.submit(ask)

        val limitBuy = order(2L, 100L, OrderSide.BUY, OrderType.LIMIT, qty = 10, price = BigDecimal("1000"))
        val results = service.submit(limitBuy)

        assertThat(results).hasSize(1)
        assertThat(results[0].price).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(limitBuy.remainingQty).isEqualTo(0)
    }

    @Test
    fun `limit buy with price less than best ask does not match and rests in book`() {
        val ask = order(1L, 100L, OrderSide.SELL, OrderType.LIMIT, qty = 10, price = BigDecimal("1000"))
        service.submit(ask)

        val limitBuy = order(2L, 100L, OrderSide.BUY, OrderType.LIMIT, qty = 10, price = BigDecimal("900"))
        val results = service.submit(limitBuy)

        assertThat(results).isEmpty()
        assertThat(limitBuy.remainingQty).isEqualTo(10)
        val book = service.getOrCreate(100L)
        assertThat(book.bids[BigDecimal("900")]!!.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `limit sell with price less or equal to best bid fills immediately`() {
        val bid = order(1L, 100L, OrderSide.BUY, OrderType.LIMIT, qty = 10, price = BigDecimal("1000"))
        service.submit(bid)

        val limitSell = order(2L, 100L, OrderSide.SELL, OrderType.LIMIT, qty = 10, price = BigDecimal("1000"))
        val results = service.submit(limitSell)

        assertThat(results).hasSize(1)
        assertThat(results[0].price).isEqualByComparingTo(BigDecimal("1000"))
        assertThat(limitSell.remainingQty).isEqualTo(0)
    }

    @Test
    fun `partial fill when order qty exceeds total available liquidity`() {
        val ask = order(1L, 100L, OrderSide.SELL, OrderType.LIMIT, qty = 5, price = BigDecimal("1000"))
        service.submit(ask)

        val limitBuy = order(2L, 100L, OrderSide.BUY, OrderType.LIMIT, qty = 20, price = BigDecimal("1000"))
        val results = service.submit(limitBuy)

        assertThat(results).hasSize(1)
        assertThat(results[0].qty).isEqualTo(5)
        assertThat(limitBuy.filledQty).isEqualTo(5)
        assertThat(limitBuy.remainingQty).isEqualTo(15)

        val book = service.getOrCreate(100L)
        assertThat(book.bids[BigDecimal("1000")]!!.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `market order with no liquidity on opposite side returns empty match list`() {
        val marketBuy = order(1L, 100L, OrderSide.BUY, OrderType.MARKET, qty = 10)
        val results = service.submit(marketBuy)

        assertThat(results).isEmpty()
        assertThat(marketBuy.remainingQty).isEqualTo(10)
    }

    @Test
    fun `cancel removes an order resting in the book and returns true`() {
        val limitBuy = order(1L, 100L, OrderSide.BUY, OrderType.LIMIT, qty = 10, price = BigDecimal("900"))
        service.submit(limitBuy)

        val cancelled = service.cancel(100L, 1L, OrderSide.BUY)

        assertThat(cancelled).isTrue()
        val book = service.getOrCreate(100L)
        assertThat(book.bids).doesNotContainKey(BigDecimal("900"))
    }

    @Test
    fun `cancel returns false for an order that does not exist in the book`() {
        val cancelled = service.cancel(100L, 999L, OrderSide.BUY)

        assertThat(cancelled).isFalse()
    }

    @Test
    fun `cancel returns false for stock with no order book at all`() {
        val cancelled = service.cancel(999L, 1L, OrderSide.BUY)

        assertThat(cancelled).isFalse()
    }

    @Test
    fun `two different stockIds get independent order books`() {
        val askStockA = order(1L, 100L, OrderSide.SELL, OrderType.LIMIT, qty = 10, price = BigDecimal("1000"))
        service.submit(askStockA)

        val marketBuyStockB = order(2L, 200L, OrderSide.BUY, OrderType.MARKET, qty = 10)
        val results = service.submit(marketBuyStockB)

        assertThat(results).isEmpty()
        assertThat(marketBuyStockB.remainingQty).isEqualTo(10)
        assertThat(askStockA.filledQty).isEqualTo(0)
    }

    @Test
    fun `price-then-time priority across non-sorted insertion order is swept ascending`() {
        val ask2 = order(1L, 100L, OrderSide.SELL, OrderType.LIMIT, qty = 5, price = BigDecimal("1020"))
        val ask1 = order(2L, 100L, OrderSide.SELL, OrderType.LIMIT, qty = 5, price = BigDecimal("1000"))
        val ask3 = order(3L, 100L, OrderSide.SELL, OrderType.LIMIT, qty = 5, price = BigDecimal("1010"))
        service.submit(ask2)
        service.submit(ask1)
        service.submit(ask3)

        val marketBuy = order(4L, 100L, OrderSide.BUY, OrderType.MARKET, qty = 15)
        val results = service.submit(marketBuy)

        assertThat(results).hasSize(3)
        assertThat(results.map { it.price }).containsExactly(
            BigDecimal("1000"), BigDecimal("1010"), BigDecimal("1020")
        )
        assertThat(results.map { it.makerOrderId }).containsExactly(2L, 3L, 1L)
    }
}
