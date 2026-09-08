package com.monticker.worker.toss

import com.fasterxml.jackson.databind.JsonNode
import com.monticker.worker.kafka.TickKafkaProducer
import com.monticker.worker.marketdata.GeneratedTick
import com.monticker.worker.marketdata.MarketSchedule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Toss "trade" 채널(trade:kr, trade:us) 파서 → market.ticks 발행.
 *
 * 메시지 형태는 AsyncAPI 스펙 예시로 직접 확인했다:
 *   {"type":"message","topic":"trade:us:AAPL","data":{"price":"243.26","volume":"8",
 *    "timestamp":"2026-06-18T23:30:00.000+09:00","currency":"USD"}}
 * 종목코드는 data가 아니라 topic("trade:{시장}:{symbol}")에만 있다.
 */
@Component
class TossExecutionTickHandler(
    private val coverage: TossCoverageProvider,
    private val producer: TickKafkaProducer,
) : TossRealtimeHandler {
    override val channelPrefix = "trade"

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(topic: String, data: JsonNode) {
        val parts = topic.split(":")
        if (parts.size < 3) return

        val symbol = parts[2]
        val target = coverage.bySymbol[symbol] ?: return

        val price = data["price"]?.asText()?.toBigDecimalOrNull()
        if (price == null) {
            log.warn("Toss trade 가격 파싱 실패: symbol={} raw={}", symbol, data["price"])
            return
        }
        val volume = data["volume"]?.asText()?.toLongOrNull() ?: 0L
        val tradeTime = parseTradeTime(data["timestamp"]?.asText())

        producer.publish(
            GeneratedTick(
                stockId = target.stockId,
                symbol = target.symbol,
                market = target.market,
                price = price,
                volume = volume,
                tradeTime = tradeTime,
                marketStatus = MarketSchedule.getTickConfig(target.symbol, target.market).status.name,
            )
        )
    }

    private fun parseTradeTime(raw: String?): Instant {
        if (raw.isNullOrBlank()) return Instant.now()
        return runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrElse { Instant.now() }
    }
}
