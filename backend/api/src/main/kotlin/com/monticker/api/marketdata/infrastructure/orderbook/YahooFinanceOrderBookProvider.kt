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
import kotlin.random.Random

/**
 * Yahoo Finance v8 API에서 현재가·최우선 호가를 가져오고
 * 2~10단계 호가 깊이는 현실적인 분포로 시뮬레이션한다.
 *
 * 활성화: application.yml에 orderbook.provider=yahoo 설정
 * Yahoo Finance는 15분 지연 데이터를 무료로 제공한다.
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
        val unit = priceUnit(price)

        // 1단계: Yahoo에서 받은 실제 최우선 호가
        val bestAsk = quote.ask?.takeIf { it > BigDecimal.ZERO }
            ?: roundToUnit(price * BigDecimal("1.001"), unit)
        val bestBid = quote.bid?.takeIf { it > BigDecimal.ZERO }
            ?: roundToUnit(price * BigDecimal("0.999"), unit)
        val bestAskQty = quote.askSize?.toLong()?.takeIf { it > 0 } ?: Random.nextLong(100, 1000)
        val bestBidQty = quote.bidSize?.toLong()?.takeIf { it > 0 } ?: Random.nextLong(100, 1000)

        // 2~10단계: 가격 간격 + 잔량 시뮬레이션 (호가 창 느낌 유지)
        val asks = buildList {
            add(OrderLevel(bestAsk, bestAskQty))
            for (i in 2..10) {
                val p = roundToUnit(bestAsk + unit * BigDecimal(i - 1), unit)
                val q = ((11 - i) * Random.nextLong(50, 500))
                add(OrderLevel(p, q))
            }
        }
        val bids = buildList {
            add(OrderLevel(bestBid, bestBidQty))
            for (i in 2..10) {
                val p = roundToUnit(bestBid - unit * BigDecimal(i - 1), unit)
                val q = ((11 - i) * Random.nextLong(50, 500))
                add(OrderLevel(p, q))
            }
        }

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
                log.warn("Yahoo Finance returned {}: {}", res.statusCode(), yahooSymbol)
                return null
            }
            val root = mapper.readTree(res.body())
            val meta = root["chart"]?.get("result")?.get(0)?.get("meta") ?: return null

            YahooQuote(
                regularMarketPrice = meta["regularMarketPrice"]?.decimalValue(),
                ask                = meta["ask"]?.decimalValue(),
                bid                = meta["bid"]?.decimalValue(),
                askSize            = meta["askSize"]?.intValue(),
                bidSize            = meta["bidSize"]?.intValue(),
            )
        } catch (e: Exception) {
            log.warn("Yahoo Finance fetch failed for {}: {}", yahooSymbol, e.message)
            null
        }
    }

    private fun toYahooSymbol(symbol: String, market: String): String = when (market.uppercase()) {
        "KOSPI"  -> "$symbol.KS"
        "KOSDAQ" -> "$symbol.KQ"
        else     -> symbol  // NASDAQ, NYSE 등 그대로
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

    private data class YahooQuote(
        val regularMarketPrice: BigDecimal?,
        val ask: BigDecimal?,
        val bid: BigDecimal?,
        val askSize: Int?,
        val bidSize: Int?,
    )
}
