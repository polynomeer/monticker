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
    /** 기동 시 기준가 — 랜덤 워크가 이 값 근처를 맴돌게 당긴다(평균 회귀) */
    private val anchorPrice = ConcurrentHashMap<Long, BigDecimal>()

    companion object {
        /**
         * 틱마다 기준가 쪽으로 차이의 이만큼을 당긴다. 틱 변동(±0.5%, 균등)만 있으면 1초 틱이 하루 수만 번 쌓여 하루 수십 %씩
         * 표류했다(로컬 점검 2026-10-09: 하루 +77%, 20일 σ 34%, VaR 55%라 모의 매수가 리스크 게이트에 막혔다).
         * 0.005면 정상 분포의 표준편차가 약 3%다 — 순간 급등락(스파이크 감지는 직전 변동의 EMA 대비라 그대로 잡힌다)은 남는다.
         */
        const val MEAN_REVERSION = 0.005

        /** 종목별 마지막 시세 — 최근 1분봉이 있으면 그것, 없으면 마지막 일봉. */
        const val LAST_PRICE_SQL = """
            SELECT s.id AS stock_id, COALESCE(m.close, d.close) AS close
            FROM stocks s
            LEFT JOIN LATERAL (
                SELECT close FROM candles_1m
                WHERE stock_id = s.id AND candle_time >= now() - interval '7 days'
                ORDER BY candle_time DESC LIMIT 1
            ) m ON true
            LEFT JOIN LATERAL (
                SELECT close FROM candles_1d WHERE stock_id = s.id ORDER BY candle_time DESC LIMIT 1
            ) d ON true
            WHERE s.is_active = true
        """
    }

    @PostConstruct
    fun loadStocks() {
        val loaded = jdbc.query(
            "SELECT id, symbol, market FROM stocks WHERE is_active = true ORDER BY id",
        ) { rs, _ -> StockMeta(rs.getLong("id"), rs.getString("symbol"), rs.getString("market")) }

        stocks.clear()
        stocks.addAll(loaded)

        // 기준가 초기화 (DB의 마지막 시세 → seed → 섹터별 기본값 → 랜덤).
        // 예전엔 DB를 보지 않아 worker를 재기동할 때마다 차트의 직전 봉과 상관없는 값(seed·랜덤)에서 다시 시작해
        // 전일 대비가 수십 %씩 튀었다(삼성전자 40,000원대 봉 다음에 71,000원).
        val last = lastKnownPrices()
        for (s in stocks) {
            val seed = SEED_PRICES[s.symbol]
            val base = when {
                last[s.id] != null -> last.getValue(s.id)
                seed != null    -> BigDecimal(seed)
                s.market == "NASDAQ" || s.market == "NYSE" -> BigDecimal(Random.nextInt(20, 500))
                s.market == "KOSPI"  -> BigDecimal(Random.nextInt(5_000, 300_000))
                else                 -> BigDecimal(Random.nextInt(1_000, 100_000))
            }
            currentPrice[s.id] = base
            anchorPrice[s.id] = base
        }

        log.info("MockPriceGenerator loaded {} stocks ({} from last known prices)", stocks.size, stocks.count { it.id in last })
    }

    /** 실패해도 기동은 계속한다 — 시세 테이블을 못 읽으면 예전처럼 seed·랜덤으로 시작한다. */
    private fun lastKnownPrices(): Map<Long, BigDecimal> = runCatching {
        jdbc.queryForList(LAST_PRICE_SQL).mapNotNull { row ->
            val id = (row["stock_id"] as? Number)?.toLong() ?: return@mapNotNull null
            val close = (row["close"] as? BigDecimal)?.takeIf { it > BigDecimal.ZERO } ?: return@mapNotNull null
            id to close
        }.toMap()
    }.getOrElse {
        log.warn("MockPriceGenerator: 마지막 시세를 읽지 못해 seed·랜덤 기준가로 시작한다: {}", it.message)
        emptyMap()
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
            val anchor = anchorPrice[s.id] ?: prev
            val pull = (anchor - prev).multiply(BigDecimal(MEAN_REVERSION))
            val change = (prev.multiply(BigDecimal(Random.nextDouble(-range, range))) + pull)
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
    private operator fun BigDecimal.minus(other: BigDecimal) = this.subtract(other)
}
