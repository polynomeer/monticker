package com.monticker.worker.marketindex

import com.monticker.worker.common.DistributedLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Date
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ADR-071 — 공급자 선택. 지금은 Mock뿐이다. 실시세 공급자를 붙이면 `market-index.provider` 값으로 고른다.
 * 알 수 없는 값이면 기동을 막는다 — 실시세를 기대한 설정이 조용히 Mock으로 돌면 안 된다.
 */
@Configuration
class MarketIndexProviderConfig {
    @Bean
    fun marketIndexProvider(@Value("\${market-index.provider:mock}") provider: String): MarketIndexProvider =
        when (provider.lowercase()) {
            "mock" -> MockMarketIndexProvider()
            else -> throw IllegalStateException("지원하지 않는 market-index.provider: $provider (지원: mock)")
        }
}

/**
 * ADR-071 — 지수·환율 시세 수집. 30초마다 공급자에서 값을 받아
 *  - market_index_quotes(지수별 최신 한 행)를 갱신하고
 *  - market_index_daily(KST 거래일별 종가)의 당일 행을 최신 값으로 덮어쓴다(장 마감 후 마지막 값이 종가).
 * 전일 종가는 market_index_daily에서 당일 이전 가장 최근 행으로 계산한다.
 */
@Component
class MarketIndexCollector(
    private val provider: MarketIndexProvider,
    private val jdbc: JdbcTemplate,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)
    /** 백필 확인은 프로세스당 한 번이면 된다(성공한 뒤에만 true). */
    private val backfillChecked = AtomicBoolean(false)

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
        /** 빈 테이블 백필 길이 — 베타(1년) 계산에 충분한 거래일 수 */
        const val BACKFILL_TRADING_DAYS = 300
    }

    @Scheduled(initialDelayString = "\${market-index.initial-delay-ms:8000}", fixedDelayString = "\${market-index.poll-ms:30000}")
    @DistributedLock(name = "market-index-collector", ttlSeconds = 25)
    fun collect() {
        try {
            collectOnce(Instant.now())
        } catch (e: Exception) {
            // 다음 주기에 다시 시도한다. 시세 화면은 마지막 값을 as_of와 함께 보여준다.
            log.warn("market index collect failed: {}", e.message)
        }
    }

    fun collectOnce(now: Instant) {
        if (!backfillChecked.get()) { backfillIfEmpty(now); backfillChecked.set(true) }
        val previous = jdbc.query("SELECT code, value FROM market_index_quotes") { rs, _ ->
            runCatching { MarketIndexCode.valueOf(rs.getString("code")) }.getOrNull() to rs.getBigDecimal("value")
        }.mapNotNull { (c, v) -> c?.let { it to v } }.toMap()

        val ticks = provider.fetch(previous, now)
        if (ticks.isEmpty()) return
        tx.executeWithoutResult { ticks.forEach { save(it) } }
    }

    private fun save(tick: MarketIndexTick) {
        val tradeDate = tick.asOf.atZone(KST).toLocalDate()
        val prevClose = jdbc.query(
            "SELECT close FROM market_index_daily WHERE code = ? AND trade_date < ? ORDER BY trade_date DESC LIMIT 1",
            { rs, _ -> rs.getBigDecimal("close") }, tick.code.name, Date.valueOf(tradeDate),
        ).firstOrNull()

        jdbc.update(
            """
            INSERT INTO market_index_quotes (code, name, value, prev_close, as_of, source, is_mocked, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (code) DO UPDATE SET
                name = EXCLUDED.name, value = EXCLUDED.value, prev_close = EXCLUDED.prev_close,
                as_of = EXCLUDED.as_of, source = EXCLUDED.source, is_mocked = EXCLUDED.is_mocked, updated_at = now()
            WHERE market_index_quotes.as_of <= EXCLUDED.as_of
            """,
            tick.code.name, tick.code.displayName, tick.value, prevClose,
            Timestamp.from(tick.asOf), provider.source, provider.isMock,
        )

        // 주말 날짜로는 일봉을 만들지 않는다(최초 기동이 주말이면 시드 값만 최신 시세로 남는다).
        if (isWeekend(tradeDate)) return
        upsertDaily(tick.code, tradeDate, tick.value)
    }

    private fun upsertDaily(code: MarketIndexCode, date: LocalDate, close: BigDecimal) {
        jdbc.update(
            """
            INSERT INTO market_index_daily (code, trade_date, close, is_mocked, updated_at)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT (code, trade_date) DO UPDATE SET close = EXCLUDED.close, is_mocked = EXCLUDED.is_mocked, updated_at = now()
            """,
            code.name, Date.valueOf(date), close, provider.isMock,
        )
    }

    /** 지수별 일봉이 하나도 없으면 공급자 이력으로 채운다(Mock은 모의 이력, 이력 없는 공급자는 건너뜀). */
    private fun backfillIfEmpty(now: Instant) {
        val today = now.atZone(KST).toLocalDate()
        for (code in MarketIndexCode.entries) {
            val count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM market_index_daily WHERE code = ?", Int::class.java, code.name,
            ) ?: 0
            if (count > 0) continue
            val history = provider.history(code, today, BACKFILL_TRADING_DAYS)
            if (history.isEmpty()) continue
            tx.executeWithoutResult { history.forEach { (d, v) -> upsertDaily(code, d, v) } }
            log.info("market_index_daily backfilled: code={} days={} mocked={}", code, history.size, provider.isMock)
        }
    }

    private fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
}
