package com.monticker.worker.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-055 — worker-event의 틱 리더는 TickKafkaConsumer·TickPipelineConfig 모두 기본 ObjectMapper
 * (FAIL_ON_UNKNOWN_PROPERTIES=true)를 쓴다. 생산자(worker-market·market-gateway)가 먼저 롤아웃돼 새 필드를
 * 실어도 소비자가 죽지 않아야 한다.
 */
class GeneratedTickWireTest {

    private val mapper = ObjectMapper().findAndRegisterModules()

    @Test
    fun `모르는 필드가 있어도 역직렬화한다`() {
        val tick = mapper.readValue(
            """{"stockId":1,"symbol":"005930","market":"KOSPI","price":71500,"volume":10,
               "tradeTime":"2026-10-04T01:00:00Z","source":"KIS","someFutureField":true}""",
            GeneratedTick::class.java,
        )
        assertThat(tick.source).isEqualTo(TickSource.KIS)
    }

    @Test
    fun `source가 없는 예전 틱은 MOCK — 실주문 쪽에서 fail-closed`() {
        val tick = mapper.readValue(
            """{"stockId":1,"symbol":"005930","market":"KOSPI","price":71500,"volume":10,"tradeTime":"2026-10-04T01:00:00Z"}""",
            GeneratedTick::class.java,
        )
        assertThat(tick.source).isEqualTo(TickSource.MOCK)
    }
}
