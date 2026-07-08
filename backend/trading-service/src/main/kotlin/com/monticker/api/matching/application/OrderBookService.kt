package com.monticker.api.matching.application

import com.monticker.api.matching.domain.Order
import com.monticker.api.matching.domain.OrderSide
import com.monticker.api.matching.domain.OrderType
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

@Service("matchingOrderBookService")
class MatchingOrderBookService {
    private val books = ConcurrentHashMap<Long, OrderBook>()

    fun getOrCreate(stockId: Long): OrderBook =
        books.getOrPut(stockId) { OrderBook(stockId) }

    fun submit(order: Order): List<MatchResult> {
        val book = getOrCreate(order.stockId)
        val results = mutableListOf<MatchResult>()

        synchronized(book) {
            when (order.orderType) {
                OrderType.MARKET -> matchMarket(book, order, results)
                OrderType.LIMIT  -> matchLimit(book, order, results)
            }
        }
        return results
    }

    fun cancel(stockId: Long, orderId: Long, side: OrderSide): Boolean {
        val book = books[stockId] ?: return false
        synchronized(book) {
            return book.removeOrder(orderId, side)
        }
    }

    private fun matchMarket(book: OrderBook, taker: Order, results: MutableList<MatchResult>) {
        val counterBook = if (taker.side == OrderSide.BUY) book.asks else book.bids
        matchAgainstBook(counterBook, taker, results, priceLimit = null)
    }

    private fun matchLimit(book: OrderBook, taker: Order, results: MutableList<MatchResult>) {
        val limitPrice = taker.limitPrice!!.amount
        val counterBook = if (taker.side == OrderSide.BUY) book.asks else book.bids

        val priceFilter: (BigDecimal) -> Boolean = if (taker.side == OrderSide.BUY) {
            { askPrice -> askPrice <= limitPrice }
        } else {
            { bidPrice -> bidPrice >= limitPrice }
        }
        matchAgainstBook(counterBook, taker, results, priceFilter)

        // If there is remaining quantity, add to book
        if (taker.remainingQty > 0) {
            if (taker.side == OrderSide.BUY) book.addBid(taker)
            else book.addAsk(taker)
        }
    }

    private fun matchAgainstBook(
        counterBook: java.util.TreeMap<BigDecimal, ArrayDeque<Order>>,
        taker: Order,
        results: MutableList<MatchResult>,
        priceLimit: ((BigDecimal) -> Boolean)?,
    ) {
        while (taker.remainingQty > 0 && counterBook.isNotEmpty()) {
            val bestEntry = counterBook.firstEntry() ?: break
            val bestPrice = bestEntry.key

            if (priceLimit != null && !priceLimit(bestPrice)) break

            val makerQueue = bestEntry.value
            if (makerQueue.isEmpty()) { counterBook.remove(bestPrice); continue }

            val maker = makerQueue.first()
            val matchQty = minOf(taker.remainingQty, maker.remainingQty)

            results.add(MatchResult(
                makerOrderId = maker.id,
                takerOrderId = taker.id,
                price = bestPrice,
                qty = matchQty,
            ))

            // Update in-memory state of maker
            maker.filledQty += matchQty
            taker.filledQty += matchQty

            if (maker.remainingQty == 0) {
                makerQueue.removeFirst()
                if (makerQueue.isEmpty()) counterBook.remove(bestPrice)
            }
        }
    }
}
