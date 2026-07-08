package com.monticker.api.matching.application

import com.monticker.api.matching.domain.Order
import com.monticker.api.matching.domain.OrderSide
import java.math.BigDecimal
import java.util.TreeMap

data class MatchResult(
    val makerOrderId: Long,
    val takerOrderId: Long,
    val price: BigDecimal,
    val qty: Int,
)

class OrderBook(val stockId: Long) {
    // asks: lowest price first
    val asks = TreeMap<BigDecimal, ArrayDeque<Order>>()
    // bids: highest price first
    val bids = TreeMap<BigDecimal, ArrayDeque<Order>>(reverseOrder())

    fun addAsk(order: Order) {
        asks.getOrPut(order.limitPrice!!.amount) { ArrayDeque() }.addLast(order)
    }

    fun addBid(order: Order) {
        bids.getOrPut(order.limitPrice!!.amount) { ArrayDeque() }.addLast(order)
    }

    fun removeOrder(orderId: Long, side: OrderSide): Boolean {
        val book = if (side == OrderSide.BUY) bids else asks
        for ((price, deque) in book) {
            val removed = deque.removeIf { it.id == orderId }
            if (removed) {
                if (deque.isEmpty()) book.remove(price)
                return true
            }
        }
        return false
    }

    fun getBestAsk(): BigDecimal? = if (asks.isEmpty()) null else asks.firstKey()
    fun getBestBid(): BigDecimal? = if (bids.isEmpty()) null else bids.firstKey()
}
