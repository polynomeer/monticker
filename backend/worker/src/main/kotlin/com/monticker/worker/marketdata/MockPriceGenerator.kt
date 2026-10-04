package com.monticker.worker.marketdata

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.monticker.worker.kis.KisCoverageProvider
import com.monticker.worker.toss.TossCoverageProvider
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// ADR-055 — 관대한 리더. worker-market·market-gateway(생산자)와 worker-event(소비자)는 별개 Deployment라
// 생산자가 먼저 롤아웃되면 새 필드를 모르는 소비자가 틱마다 실패해 전부 DLT로 간다(아래 seq 주석의 사고).
@JsonIgnoreProperties(ignoreUnknown = true)
data class GeneratedTick(
    val stockId: Long,
    val symbol: String,
    val market: String,
    val price: BigDecimal,
    val volume: Long,
    val tradeTime: Instant,
    val generatedAt: Instant = Instant.now(),
    val marketStatus: String = "OPEN",
    // 종목별 단조 증가 시퀀스. Go market-gateway가 TICK_SEQ=true 일 때만 채운다(실험 M-002) — 없으면 null.
    // ObjectMapper 기본값이 FAIL_ON_UNKNOWN_PROPERTIES=true 라 필드 없이는 seq가 실린 틱이 전부 DLT로 간다.
    val seq: Long? = null,
    // ADR-055 — 시세 출처. 기본값이 MOCK인 건 의도다: 출처를 빠뜨린 생산자는 실주문 경로(조건부 주문)에서
    // "실시세 아님"으로 취급돼 fail-closed 된다. 실시세 생산자(KIS·Toss)는 반드시 명시한다.
    val source: String = TickSource.MOCK,
)

/** ADR-055 — market.ticks 와이어의 `source` 값. api의 PriceSource와 문자열로 맞춘다. */
object TickSource {
    const val MOCK = "MOCK"
    const val KIS = "KIS"
    const val TOSS = "TOSS"
}

private data class StockMeta(val id: Long, val symbol: String, val market: String)

@Component
class MockPriceGenerator(
    private val jdbc: JdbcTemplate,
    private val kisCoverage: KisCoverageProvider,
    private val tossCoverage: TossCoverageProvider,
) {

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
            // ADR-030/031 — KIS/Toss 실시간체결가가 실제로 구독한 종목은 Mock을 건너뛴다.
            // "시장 전체 제외"가 아니라 두 프로바이더의 실제 커버리지 합집합만큼만 대체한다.
            if (s.id in kisCoverage.coveredStockIds || s.id in tossCoverage.coveredStockIds) return@mapNotNull null

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
