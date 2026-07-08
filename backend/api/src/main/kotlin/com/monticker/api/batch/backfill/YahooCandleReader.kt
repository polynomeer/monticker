package com.monticker.api.batch.backfill

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ExecutionContext
import org.springframework.batch.item.ItemStreamReader
import org.springframework.web.client.RestTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Yahoo Finance v8 chart API (비공식, 지연 데이터)로 일봉 OHLCV를 읽는다.
 * API가 실패하면 MockCandleGenerator로 폴백한다.
 */
open class YahooCandleReader(
    private val symbol: String,
    private val stockId: Long,
    private val fromDate: LocalDate,
    private val toDate: LocalDate,
    private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper,
) : ItemStreamReader<CandleOhlcv> {

    private val log = LoggerFactory.getLogger(javaClass)
    private val zone = ZoneId.of("Asia/Seoul")
    private val candles = ArrayDeque<CandleOhlcv>()

    override fun open(executionContext: ExecutionContext) {
        val fetched = tryFetchFromYahoo() ?: generateMock()
        candles.addAll(fetched)
        log.info("CandleBackfill: stockId={} symbol={} {} ~ {} → {} candles",
            stockId, symbol, fromDate, toDate, candles.size)
    }

    override fun read(): CandleOhlcv? = candles.removeFirstOrNull()

    override fun update(executionContext: ExecutionContext) {}
    override fun close() {}

    private fun tryFetchFromYahoo(): List<CandleOhlcv>? = runCatching {
        val from = fromDate.atStartOfDay(zone).toEpochSecond()
        val to   = toDate.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val url  = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol" +
                   "?interval=1d&period1=$from&period2=$to"

        val json = restTemplate.getForObject(url, String::class.java) ?: return null
        parseYahooResponse(objectMapper.readTree(json))
    }.getOrElse { e ->
        log.warn("Yahoo Finance 조회 실패 symbol={}: {}", symbol, e.message)
        null
    }

    private fun parseYahooResponse(root: JsonNode): List<CandleOhlcv>? {
        val result = root.path("chart").path("result").firstOrNull() ?: return null
        val timestamps = result.path("timestamp")
        val quote = result.path("indicators").path("quote").firstOrNull() ?: return null

        if (timestamps.isEmpty || quote.path("close").isEmpty) return null

        return timestamps.mapIndexed { i, ts ->
            val date = Instant.ofEpochSecond(ts.longValue()).atZone(zone).toLocalDate()
            CandleOhlcv(
                stockId = stockId,
                date    = date,
                open    = quote.path("open").get(i)?.decimalValue()?.setScale(2, RoundingMode.HALF_UP) ?: BigDecimal.ZERO,
                high    = quote.path("high").get(i)?.decimalValue()?.setScale(2, RoundingMode.HALF_UP) ?: BigDecimal.ZERO,
                low     = quote.path("low").get(i)?.decimalValue()?.setScale(2, RoundingMode.HALF_UP) ?: BigDecimal.ZERO,
                close   = quote.path("close").get(i)?.decimalValue()?.setScale(2, RoundingMode.HALF_UP) ?: BigDecimal.ZERO,
                volume  = quote.path("volume").get(i)?.longValue() ?: 0L,
            )
        }.filter { it.close > BigDecimal.ZERO }
    }

    private fun generateMock(): List<CandleOhlcv> {
        val result = mutableListOf<CandleOhlcv>()
        var price = BigDecimal("50000")
        var date = fromDate

        while (!date.isAfter(toDate)) {
            val change = BigDecimal((Math.random() * 4 - 2) / 100.0 + 1.0)
            price = price.multiply(change).setScale(0, RoundingMode.HALF_UP)
            val high = price.multiply(BigDecimal("1.02")).setScale(0, RoundingMode.HALF_UP)
            val low  = price.multiply(BigDecimal("0.98")).setScale(0, RoundingMode.HALF_UP)
            result.add(CandleOhlcv(stockId, date, price, high, low, price, (Math.random() * 500000 + 100000).toLong()))
            date = date.plusDays(1)
        }
        return result
    }
}
