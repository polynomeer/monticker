package com.monticker.worker.marketdata

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.DefaultTransactionStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class CandleAggregatorTest {
    private val jdbc = mockk<JdbcTemplate>(relaxed = true)
    private val txm = mockk<PlatformTransactionManager>(relaxed = true).also {
        every { it.getTransaction(any()) } returns mockk<DefaultTransactionStatus>(relaxed = true)
    }
    private val agg = CandleAggregator(jdbc, txm, SimpleMeterRegistry())

    private fun tick(stock: Long, at: Instant, price: Long) = GeneratedTick(
        stockId = stock, symbol = "S$stock", market = "KOSPI", price = BigDecimal(price), volume = 10,
        tradeTime = at, generatedAt = at,
    )

    @Test
    fun `onTick does no DB work inline, even across a minute roll`() {
        val m0 = Instant.parse("2026-09-17T00:18:30Z")
        agg.onTick(tick(1, m0, 100))
        agg.onTick(tick(1, m0.plus(1, ChronoUnit.MINUTES), 101))   // 분 경계 — 이전 구현은 여기서 flush 했다
        verify(exactly = 0) { jdbc.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
        verify(exactly = 0) { jdbc.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `drainCompleted batch-upserts completed candles into both tables`() {
        val m0 = Instant.parse("2026-09-17T00:18:30Z")
        val m1 = m0.plus(1, ChronoUnit.MINUTES)
        // 세 종목이 한 분을 채우고 다음 분으로 넘어간다 → 완결 캔들 3개가 큐에 쌓인다
        for (s in 1L..3L) { agg.onTick(tick(s, m0, 100)); agg.onTick(tick(s, m1, 101)) }
        agg.drainCompleted()
        // candles_1m 과 candles_1d 각각 한 번의 batchUpdate, 배치 크기 3
        val sizes = mutableListOf<Int>()
        verify(exactly = 2) { jdbc.batchUpdate(any<String>(), match<BatchPreparedStatementSetter> { sizes.add(it.batchSize); true }) }
        assert(sizes.all { it == 3 }) { "expected batch size 3, got $sizes" }
    }

    @Test
    fun `flushAll drains queue and current-minute candles`() {
        val m0 = Instant.parse("2026-09-17T00:18:30Z")
        val m1 = m0.plus(1, ChronoUnit.MINUTES)
        agg.onTick(tick(1, m0, 100)); agg.onTick(tick(1, m1, 101))   // 1개 완결(큐), 1개 진행 중(state)
        agg.onTick(tick(2, m1, 100))                                  // 진행 중 1개 더
        agg.flushAll()
        // 큐 drain(배치1) + 현재 분 캔들(배치1) = 테이블당 최대 2회 batchUpdate → 총 4회
        verify(atLeast = 2) { jdbc.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
    }
}
