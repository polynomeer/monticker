package com.monticker.api.brokerage.application

import com.monticker.api.brokerage.infrastructure.BrokerageOrderRequest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Instant

class OrderPriceGuardTest {
    private val jdbc = mockk<JdbcTemplate>()
    private val feed = mockk<PriceFeedMonitor> { every { isCovered(any()) } returns true }
    private val meters = SimpleMeterRegistry()
    private val guard = OrderPriceGuard(jdbc, feed, meters)
    private val now = Instant.parse("2026-10-05T02:00:00Z")   // KST 11:00 장중

    private fun stub(market: String? = "KOSPI", base: BigDecimal? = BigDecimal("70000"), current: BigDecimal? = BigDecimal("71000")) {
        every { jdbc.queryForList("SELECT market FROM stocks WHERE id = ?", String::class.java, 1L) } returns listOfNotNull(market)
        every { jdbc.queryForList(match<String> { it.contains("candles_1d") }, BigDecimal::class.java, *anyVararg()) } returns listOfNotNull(base)
        every { jdbc.queryForList(match<String> { it.contains("candles_1m") }, BigDecimal::class.java, *anyVararg()) } returns listOfNotNull(current)
    }

    private fun limit(price: String) = BrokerageOrderRequest("005930", "BUY", "LIMIT", 1, BigDecimal(price))

    private fun skipped(reason: String) =
        meters.find("brokerage_order_price_check_total").tags("result", "band_skipped", "reason", reason).counter()?.count() ?: 0.0

    @Test
    fun `호가 단위가 틀리면 400 메시지에 단위와 가까운 가격을 담는다`() {
        stub()
        assertThatThrownBy { guard.check(1L, limit("70050"), movesRealMoney = { true }, now = now) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("100원")
            .hasMessageContaining("70,000원 또는 70,100원")
    }

    @Test
    fun `지정가 0 이하는 거부한다`() {
        stub()
        assertThatThrownBy { guard.check(1L, limit("0"), movesRealMoney = { false }, now = now) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `전일 종가 ±30% 밖이면 거부한다`() {
        stub()
        assertThatThrownBy { guard.check(1L, limit("91100"), movesRealMoney = { true }, now = now) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("가격제한폭")
            .hasMessageContaining("49,000원 ~ 91,000원")
        assertThatThrownBy { guard.check(1L, limit("48900"), movesRealMoney = { true }, now = now) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `제한폭 안이고 호가 단위가 맞으면 통과한다`() {
        stub()
        guard.check(1L, limit("91000"), movesRealMoney = { true }, now = now)
        guard.check(1L, limit("49000"), movesRealMoney = { true }, now = now)
    }

    @Test
    fun `실계좌인데 실시세 커버리지 밖이면 기준가를 믿지 않고 제한폭 검사를 건너뛴다 - 호가 단위는 그대로 건다`() {
        stub()
        every { feed.isCovered(1L) } returns false
        guard.check(1L, limit("150000"), movesRealMoney = { true }, now = now)
        assertThat(skipped("unverified_source")).isEqualTo(1.0)
        assertThatThrownBy { guard.check(1L, limit("150050"), movesRealMoney = { true }, now = now) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Mock 증권사 계좌는 커버리지와 무관하게 제한폭을 건다`() {
        stub()
        every { feed.isCovered(any()) } returns false
        assertThatThrownBy { guard.check(1L, limit("150000"), movesRealMoney = { false }, now = now) }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { feed.isCovered(any()) }
    }

    @Test
    fun `현재가가 계산한 폭 밖이면(권리락·분할) 기준가가 틀린 것으로 보고 건너뛴다`() {
        stub(base = BigDecimal("100000"), current = BigDecimal("20000"))   // 5:1 분할 당일
        guard.check(1L, limit("20000"), movesRealMoney = { true }, now = now)
        assertThat(skipped("inconsistent_base")).isEqualTo(1.0)
    }

    @Test
    fun `기준가나 오늘 시세가 없으면 건너뛴다`() {
        stub(base = null)
        guard.check(1L, limit("150000"), movesRealMoney = { true }, now = now)
        stub(current = null)
        guard.check(1L, limit("150000"), movesRealMoney = { true }, now = now)
        assertThat(skipped("no_base")).isEqualTo(1.0)
        assertThat(skipped("no_current")).isEqualTo(1.0)
    }

    @Test
    fun `KRX가 아니거나 시장가면 검사하지 않는다`() {
        stub(market = "NASDAQ")
        guard.check(1L, BrokerageOrderRequest("AAPL", "BUY", "LIMIT", 1, BigDecimal("187.53")), movesRealMoney = { true }, now = now)
        guard.check(1L, BrokerageOrderRequest("005930", "BUY", "MARKET", 1), movesRealMoney = { true }, now = now)
    }
}
