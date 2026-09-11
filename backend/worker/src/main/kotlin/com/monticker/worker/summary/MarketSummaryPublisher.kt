package com.monticker.worker.summary

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.Properties

const val MARKET_SUMMARY_TOPIC = "market.summary"
const val MARKET_SUMMARY_REDIS_KEY = "market:summary"

/**
 * ADR-039 — /topic/market 전역 브로드캐스트를 대체하는 1초 1회 시장 요약.
 *
 * 전역 토픽은 틱 레이트 × 구독자 수를 곱했다(L-02 기준선: 500 연결에서 50,000 msg/s, 91%가 이것).
 * 홈 위젯이 실제로 원하는 건 "시장 전체의 요약"이지 "모든 종목의 모든 틱"이 아니다.
 * 여기서 1초마다 계산해 Redis(REST 폴백·초기 렌더)와 Kafka(모든 api pod가 STOMP로 릴레이)에 싣는다.
 * 발송량은 구독자 수 × 1 msg/s로 고정된다 — 틱 레이트와 무관하다.
 *
 * 계산은 ScreenerRepository와 같은 LATERAL 조인이다(전 종목 1회/초). 유니버스가 커지면
 * 스크리너 ZSET 사전 계산(scale-out-plan §6.4.3)에서 읽도록 바꾼다.
 */
@Component
@ConditionalOnExpression("'\${worker.role:all}'.matches('market|all')")
class MarketSummaryPublisher(
    private val jdbc: JdbcTemplate,
    private val redis: StringRedisTemplate,
    @Value("\${kafka.brokers:localhost:9092}") brokers: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val producer = KafkaProducer<String, String>(Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000)   // 브로커가 없어도 스케줄러 스레드가 오래 묶이지 않게
    })

    data class Row(val stockId: Long, val symbol: String, val name: String, val market: String,
                   val price: BigDecimal, val prevClose: BigDecimal?, val volume: Long, val amount: BigDecimal) {
        val changeRate: Double? get() = prevClose?.takeIf { it.signum() > 0 }
            ?.let { ((price - it).toDouble() / it.toDouble()) * 100 }
    }

    @Scheduled(fixedRate = 1000, initialDelay = 5000)
    fun publish() {
        val summary = try { compute() } catch (e: Exception) {
            log.warn("[MarketSummary] 계산 실패: {}", e.message); return
        }
        val json = objectMapper.writeValueAsString(summary)
        runCatching { redis.opsForValue().set(MARKET_SUMMARY_REDIS_KEY, json, Duration.ofSeconds(5)) }
            .onFailure { log.warn("[MarketSummary] Redis 저장 실패: {}", it.message) }
        producer.send(ProducerRecord(MARKET_SUMMARY_TOPIC, "summary", json)) { _, ex ->
            if (ex != null) log.warn("[MarketSummary] Kafka 발행 실패: {}", ex.message)
        }
    }

    fun compute(): Map<String, Any?> {
        val rows = jdbc.query(
            """
            SELECT s.id, s.symbol, s.name, s.market,
                   COALESCE(c.close, 0) AS price, COALESCE(c.volume, 0) AS volume,
                   COALESCE(c.close * c.volume, 0) AS amount, prev.close AS prev_close
            FROM stocks s
            LEFT JOIN LATERAL (SELECT close, volume FROM candles_1m WHERE stock_id = s.id ORDER BY candle_time DESC LIMIT 1) c ON true
            LEFT JOIN LATERAL (SELECT close FROM candles_1d WHERE stock_id = s.id ORDER BY candle_time DESC LIMIT 1 OFFSET 1) prev ON true
            WHERE s.is_active = true
            """.trimIndent(),
        ) { rs, _ ->
            Row(rs.getLong("id"), rs.getString("symbol"), rs.getString("name"), rs.getString("market"),
                rs.getBigDecimal("price"), rs.getBigDecimal("prev_close"), rs.getLong("volume"), rs.getBigDecimal("amount"))
        }.filter { it.price.signum() > 0 }

        val withRate = rows.filter { it.changeRate != null }
        fun item(r: Row) = mapOf("stockId" to r.stockId, "symbol" to r.symbol, "name" to r.name, "market" to r.market,
            "price" to r.price, "changeRate" to r.changeRate, "volume" to r.volume, "amount" to r.amount)
        return mapOf(
            "asOf"        to Instant.now().toString(),
            "stockCount"  to rows.size,
            "advancers"   to withRate.count { it.changeRate!! > 0 },
            "decliners"   to withRate.count { it.changeRate!! < 0 },
            "unchanged"   to withRate.count { it.changeRate == 0.0 },
            "topByAmount" to rows.sortedByDescending { it.amount }.take(10).map(::item),
            "topGainers"  to withRate.sortedByDescending { it.changeRate!! }.take(10).map(::item),
            "topLosers"   to withRate.sortedBy { it.changeRate!! }.take(10).map(::item),
        )
    }
}
