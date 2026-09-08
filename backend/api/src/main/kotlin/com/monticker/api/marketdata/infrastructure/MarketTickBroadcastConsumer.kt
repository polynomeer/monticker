package com.monticker.api.marketdata.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.monticker.api.marketdata.domain.PriceTick
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * ADR-029 — market.ticks를 직접 구독해 PriceBroadcaster로 STOMP 푸시한다.
 *
 * backend/worker(별도 프로세스)의 TickKafkaConsumer가 이미 같은 토픽을 소비하지만,
 * PriceBroadcaster(STOMP 발행)는 backend/api에만 있어 worker에서 직접 호출할 수 없다 —
 * 두 서비스가 공유하는 유일한 실시간 버스인 Kafka를 그대로 재사용해 독립 컨슈머 그룹으로
 * 구독한다. groupId를 worker의 monticker-worker와 분리해 서로의 리밸런스/장애가 전파되지
 * 않게 한다.
 */
@Component
class MarketTickBroadcastConsumer(
    private val priceBroadcaster: PriceBroadcaster,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @KafkaListener(topics = ["market.ticks"], groupId = "monticker-api-broadcast")
    fun onTick(record: ConsumerRecord<String, String>) {
        runCatching {
            val wire = objectMapper.readValue(record.value(), MarketTickMessage::class.java)
            priceBroadcaster.broadcast(
                PriceTick(
                    stockId   = wire.stockId,
                    symbol    = wire.symbol,
                    price     = wire.price,
                    volume    = wire.volume,
                    tradeTime = wire.tradeTime,
                )
            )
        }.onFailure { e ->
            log.warn("[MarketTickBroadcast] 틱 처리 실패: {}", e.message)
        }
    }

    // backend/worker의 GeneratedTick(및 Go market-gateway의 Tick)과 필드명을 맞춰야 한다 —
    // 스키마 레지스트리 없이 관례로만 동기화된다(ADR-005 참고).
    private data class MarketTickMessage(
        val stockId: Long,
        val symbol: String,
        val market: String,
        val price: BigDecimal,
        val volume: Long,
        val tradeTime: Instant,
        val generatedAt: Instant = Instant.now(),
        val marketStatus: String = "OPEN",
    )
}
