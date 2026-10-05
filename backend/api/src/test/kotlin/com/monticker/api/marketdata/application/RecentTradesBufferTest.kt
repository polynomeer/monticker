package com.monticker.api.marketdata.application

import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import com.monticker.api.marketdata.domain.PriceSource
import com.monticker.api.marketdata.domain.PriceTick
import com.monticker.api.marketdata.domain.TickProvenance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class RecentTradesBufferTest {
    private fun tick(stockId: Long, price: String, at: Long) = MarketTickReceivedEvent(
        PriceTick(stockId, "S$stockId", BigDecimal(price), 10, Instant.ofEpochSecond(at)),
        TickProvenance(PriceSource.MOCK, "OPEN", Instant.ofEpochSecond(at)),
    )

    @Test
    fun `returns newest first with the direction against the previous tick`() {
        val buf = RecentTradesBuffer(capacity = 10, maxStocks = 10)
        buf.onTick(tick(1, "100", 1)); buf.onTick(tick(1, "101", 2)); buf.onTick(tick(1, "99", 3)); buf.onTick(tick(1, "99", 4))

        val r = buf.recent(1, 10)
        assertThat(r.map { it.price.toPlainString() }).containsExactly("99", "99", "101", "100")
        assertThat(r.map { it.direction }).containsExactly("FLAT", "DOWN", "UP", "FLAT")
        assertThat(r.first().source).isEqualTo("MOCK")
    }

    @Test
    fun `keeps only the last capacity ticks and honours the limit`() {
        val buf = RecentTradesBuffer(capacity = 3, maxStocks = 10)
        (1..5).forEach { buf.onTick(tick(1, "$it", it.toLong())) }
        assertThat(buf.recent(1, 50).map { it.price.toInt() }).containsExactly(5, 4, 3)
        assertThat(buf.recent(1, 1).map { it.price.toInt() }).containsExactly(5)
        assertThat(buf.recent(2, 10)).isEmpty()
    }

    @Test
    fun `stops tracking new stocks past the stock cap`() {
        val buf = RecentTradesBuffer(capacity = 3, maxStocks = 1)
        buf.onTick(tick(1, "1", 1)); buf.onTick(tick(2, "1", 1))
        assertThat(buf.recent(2, 10)).isEmpty()
        assertThat(buf.recent(1, 10)).hasSize(1)
    }
}
