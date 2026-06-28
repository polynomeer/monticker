package com.monticker.api.marketdata.infrastructure.orderbook

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.random.Random

/**
 * Yahoo Finance v8 chart API에서 현재가와 당일 고/저/거래량을 가져온다.
 * KRX 종목은 Yahoo가 bid/ask를 제공하지 않으므로
 * 현재가를 기준으로 호가 깊이를 시뮬레이션한다.
 *
 * 제공 데이터:
 *   regularMarketPrice  — 현재가 (실데이터, 15분 지연)
 *   regularMarketDayHigh/Low — 당일 고/저 (변동성 추정에 사용)
 *   regularMarketVolume — 당일 거래량 (잔량 스케일 보정)
 *
 * 활성화: ORDERBOOK_PROVIDER=yahoo 환경변수
 *
 * 심볼 매핑:
 *   KOSPI  → {symbol}.KS  (예: 005930 → 005930.KS)
 *   KOSDAQ → {symbol}.KQ  (예: 035720 → 035720.KQ)
 *   NASDAQ/NYSE → 그대로  (예: AAPL)
 */
@Component
@ConditionalOnProperty(name = ["orderbook.provider"], havingValue = "yahoo")
class YahooFinanceOrderBookProvider : OrderBookProvider {

    private val log = LoggerFactory.getLogger(javaClass)
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val mapper = ObjectMapper()

    override fun getOrderBook(symbol: String, market: String, refPrice: BigDecimal): OrderBookSnapshot? {
        val yahooSymbol = toYahooSymbol(symbol, market)
        val quote = fetchQuote(yahooSymbol) ?: return null

        val price = quote.regularMarketPrice ?: refPrice
        val unit  = priceUnit(price)

        // 당일 변동성으로 호가 단계 간격 조정
        // 변동성이 크면 호가 간격을 넓게, 작으면 좁게
        val dayRange = quote.dayHigh - quote.dayLow
        val volatilityRatio = if (price > BigDecimal.ZERO) dayRange / price else BigDecimal("0.005")
        val stepMultiplier  = max(1, (volatilityRatio * BigDecimal("200")).toInt())

        // 당일 거래량 기반 잔량 스케일 (거래량이 많을수록 호가 잔량도 많다)
        val volumeBase = max(100L, quote.dailyVolume / 1000)

        val asks = (1..10).map { i ->
            val p = roundToUnit(price + unit * BigDecimal(i * stepMultiplier), unit)
            // 호가가 멀어질수록 잔량 감소 (실제 호가창 분포 모사)
            val q = (11 - i) * Random.nextLong(volumeBase / 10, volumeBase)
            OrderLevel(p, max(1L, q))
        }
        val bids = (1..10).map { i ->
            val p = roundToUnit(price - unit * BigDecimal(i * stepMultiplier), unit)
            val q = (11 - i) * Random.nextLong(volumeBase / 10, volumeBase)
            OrderLevel(p, max(1L, q))
        }.filter { it.price > BigDecimal.ZERO }

        return OrderBookSnapshot(asks, bids, Instant.now(), DataSource.YAHOO_FINANCE)
    }

    private fun fetchQuote(yahooSymbol: String): YahooQuote? {
        return try {
            val url = "https://query2.finance.yahoo.com/v8/finance/chart/$yahooSymbol?interval=1m&range=1d"
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                log.warn("Yahoo Finance {}: status={}", yahooSymbol, res.statusCode())
                return null
            }
            val meta = mapper.readTree(res.body())
                ?.get("chart")?.get("result")?.get(0)?.get("meta") ?: return null

            YahooQuote(
                regularMarketPrice = meta["regularMarketPrice"]?.decimalValue(),
                dayHigh            = meta["regularMarketDayHigh"]?.decimalValue() ?: BigDecimal.ZERO,
                dayLow             = meta["regularMarketDayLow"]?.decimalValue()  ?: BigDecimal.ZERO,
                dailyVolume        = meta["regularMarketVolume"]?.longValue()      ?: 0L,
            )
        } catch (e: Exception) {
            log.warn("Yahoo Finance fetch failed for {}: {}", yahooSymbol, e.message)
            null
        }
    }

    private fun toYahooSymbol(symbol: String, market: String): String = when (market.uppercase()) {
        "KOSPI"  -> "$symbol.KS"
        "KOSDAQ" -> "$symbol.KQ"
        else     -> symbol
    }

    private fun priceUnit(price: BigDecimal): BigDecimal = when {
        price >= BigDecimal("500000") -> BigDecimal("1000")
        price >= BigDecimal("100000") -> BigDecimal("500")
        price >= BigDecimal("50000")  -> BigDecimal("100")
        price >= BigDecimal("10000")  -> BigDecimal("50")
        price >= BigDecimal("5000")   -> BigDecimal("10")
        price >= BigDecimal("1000")   -> BigDecimal("5")
        price >= BigDecimal("500")    -> BigDecimal("1")
        else                          -> BigDecimal("0.1")
    }

    private fun roundToUnit(price: BigDecimal, unit: BigDecimal): BigDecimal =
        price.divide(unit, 0, RoundingMode.HALF_UP).multiply(unit)

    private operator fun BigDecimal.plus(other: BigDecimal)  = this.add(other)
    private operator fun BigDecimal.minus(other: BigDecimal) = this.subtract(other)
    private operator fun BigDecimal.times(other: BigDecimal) = this.multiply(other)
    private operator fun BigDecimal.div(other: BigDecimal)   =
        if (other.signum() == 0) BigDecimal.ZERO else this.divide(other, 6, RoundingMode.HALF_UP)

    private data class YahooQuote(
        val regularMarketPrice: BigDecimal?,
        val dayHigh: BigDecimal,
        val dayLow: BigDecimal,
        val dailyVolume: Long,
    )
}
