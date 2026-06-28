package com.monticker.worker.marketdata

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class GeneratedTick(
    val stockId: Long,
    val symbol: String,
    val market: String,
    val price: BigDecimal,
    val volume: Long,
    val tradeTime: Instant,
    val generatedAt: Instant = Instant.now(),
    val marketStatus: String = "OPEN",
)

private data class StockMeta(val id: Long, val symbol: String, val market: String)

@Component
class MockPriceGenerator(private val jdbc: JdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 시장별 대표 기준가 (없으면 랜덤 생성)
    private val SEED_PRICES = mapOf(
        "005930" to 71_000, "000660" to 180_000, "005380" to 280_000,
        "035420" to 210_000, "035720" to 45_000,  "051910" to 380_000,
        "005490" to 520_000, "000270" to 95_000,  "068270" to 180_000,
        "105560" to 68_000,  "055550" to 46_000,  "373220" to 380_000,
        "247540" to 150_000, "086520" to 65_000,
        "AAPL" to 220, "MSFT" to 440, "NVDA" to 140, "GOOGL" to 185,
        "META" to 580, "AMZN" to 210, "TSLA" to 250, "AMD" to 175,
    )

    private val stocks = mutableListOf<StockMeta>()
    private val currentPrice = ConcurrentHashMap<Long, BigDecimal>()

    @PostConstruct
    fun loadStocks() {
        val loaded = jdbc.query(
            "SELECT id, symbol, market FROM stocks WHERE is_active = true ORDER BY id",
        ) { rs, _ -> StockMeta(rs.getLong("id"), rs.getString("symbol"), rs.getString("market")) }

        stocks.clear()
        stocks.addAll(loaded)

        // 기준가 초기화 (seed → 섹터별 기본값 → 랜덤)
        for (s in stocks) {
            val seed = SEED_PRICES[s.symbol]
            val base = when {
                seed != null    -> BigDecimal(seed)
                s.market == "NASDAQ" || s.market == "NYSE" -> BigDecimal(Random.nextInt(20, 500))
                s.market == "KOSPI"  -> BigDecimal(Random.nextInt(5_000, 300_000))
                else                 -> BigDecimal(Random.nextInt(1_000, 100_000))
            }
            currentPrice[s.id] = base
        }

        log.info("MockPriceGenerator loaded {} stocks", stocks.size)
    }

    fun generate(): List<GeneratedTick> {
        return stocks.mapNotNull { s ->
            val config = MarketSchedule.getTickConfig(s.symbol, s.market)
            // 장 마감 중에도 개발 편의를 위해 낮은 변동성으로 틱 생성 (실서비스에서는 제거)
            val effectiveStatus = if (config.status == MarketSchedule.MarketStatus.CLOSED)
                MarketSchedule.MarketStatus.POST_MARKET else config.status
            val effectiveMultiplier = if (config.status == MarketSchedule.MarketStatus.CLOSED)
                0.1 else config.volatilityMultiplier

            val prev  = currentPrice[s.id] ?: return@mapNotNull null
            val range = 0.005 * effectiveMultiplier
            val change = prev.multiply(BigDecimal(Random.nextDouble(-range, range)))
                .setScale(if (s.market == "NASDAQ" || s.market == "NYSE") 2 else 0, RoundingMode.HALF_UP)
            val next = (prev + change).coerceAtLeast(BigDecimal("0.01"))
            currentPrice[s.id] = next

            GeneratedTick(
                stockId      = s.id,
                symbol       = s.symbol,
                market       = s.market,
                price        = next,
                volume       = if (config.status == MarketSchedule.MarketStatus.OPEN)
                                   Random.nextLong(1_000, 50_000)
                               else Random.nextLong(100, 2_000),
                tradeTime    = Instant.now(),
                generatedAt  = Instant.now(),
                marketStatus = effectiveStatus.name,
            )
        }
    }

    private operator fun BigDecimal.plus(other: BigDecimal) = this.add(other)
}
