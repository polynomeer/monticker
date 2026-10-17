package com.monticker.worker.common

import com.monticker.worker.detector.StockEventDetectedEvent
import com.monticker.worker.detector.StockEventWriter
import com.monticker.worker.search.SearchIndexEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.kafka.support.converter.ByteArrayJacksonJsonMessageConverter
import org.springframework.messaging.support.MessageBuilder
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * Boot가 구성한 매퍼(Jackson 3)로 쓰는 worker 아웃박스 이벤트의 형식을 고정한다(ADR-104, B4-5).
 *
 * 이 문자열이 `worker_outbox.event_publication.serialized_event`에 저장되고, 같은 매퍼를 쓰는
 * `ByteArrayJacksonJsonMessageConverter`가 Kafka로 내보낸다. api가 읽는다 — 같은 문자열을 api의
 * WireFormatGoldenTest가 읽기 쪽에서 고정한다. 매퍼 설정은 **실제 application.yml**에서 읽는다.
 *
 * 참고: Jackson 3의 알파벳 정렬은 생성자 속성을 앞에 두므로(SORT_CREATOR_PROPERTIES_FIRST) data class 출력은
 * `use-jackson2-defaults`를 꺼도 같다. 그 설정이 지키는 건 생성자 밖 속성의 순서와 읽기 기본값(primitive 자리의 null 거부 등)이다.
 */
class BootJsonFormatGoldenTest {

    private fun withBootMapper(block: (JsonMapper) -> Any?) {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
            .run { ctx -> block(ctx.getBean(JsonMapper::class.java)) }
    }

    private val detectedJson =
        """{"eventId":321,"stockId":5930,"eventType":"PRICE_SPIKE","importanceScore":80,"eventTimeMillis":1792112400123}"""

    @Test
    fun `market event-detected — api가 읽는 형식`() = withBootMapper { m ->
        assertThat(m.writeValueAsString(StockEventDetectedEvent(321, 5930, "PRICE_SPIKE", 80, 1792112400123))).isEqualTo(detectedJson)
        assertThat(m.readValue(detectedJson, StockEventDetectedEvent::class.java))
            .isEqualTo(StockEventDetectedEvent(321, 5930, "PRICE_SPIKE", 80, 1792112400123))
    }

    @Test
    fun `search index — null 값도 그대로 쓴다(api 색인 문서의 필드를 비운다)`() = withBootMapper { m ->
        val payload = StockEventWriter.searchPayload(
            5930, "PRICE_SPIKE", "급등", null, Instant.parse("2026-10-16T01:00:00.123Z"), 80, "DETECTOR",
        )
        assertThat(m.writeValueAsString(SearchIndexEvent.index("stock_events", "321", payload))).isEqualTo(
            """{"index":"stock_events","docId":"321","op":"INDEX","payload":{"stockId":5930,"eventType":"PRICE_SPIKE","title":"급등",""" +
                """"description":null,"eventTime":1792112400123,"importanceScore":80,"sentimentScore":null,"sourceType":"DETECTOR"}}""",
        )
    }

    @Test
    fun `Kafka 외부화 — 컨버터가 같은 매퍼로 같은 바이트를 낸다`() = withBootMapper { m ->
        val record = ByteArrayJacksonJsonMessageConverter(m)
            .fromMessage(MessageBuilder.withPayload(StockEventDetectedEvent(321, 5930, "PRICE_SPIKE", 80, 1792112400123)).build(), "market.event-detected")
        assertThat(String(record.value() as ByteArray)).isEqualTo(detectedJson)
    }
}
