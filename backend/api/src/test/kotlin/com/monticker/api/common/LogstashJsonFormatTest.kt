package com.monticker.api.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import com.monticker.api.support.TestJson
import net.logstash.logback.encoder.LogstashEncoder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 운영 로그(logback-spring.xml의 prod 프로필, LogstashEncoder)의 JSON 필드를 고정한다 — Loki·ELK 질의와 대시보드가
 * 이 이름에 기대고 있다. logstash-logback-encoder 9(Jackson 3 기반, B4-5)로 올리며 필드 이름이 그대로인지 본다.
 */
class LogstashJsonFormatTest {

    @Test
    fun `prod JSON log keeps the field names dashboards query`() {
        val context = LoggerContext()
        // logback-spring.xml의 prod 설정과 같게
        val encoder = LogstashEncoder().apply {
            this.context = context
            fieldNames.timestamp = "@timestamp"
            fieldNames.version = "[ignore]"
            addIncludeMdcKeyName("requestId")
            addIncludeMdcKeyName("userId")
            start()
        }
        val event = LoggingEvent(
            "fqcn", context.getLogger("com.monticker.api.Probe"), Level.WARN, "주문 {} 실패", null, arrayOf<Any>(7),
        ).apply { mdcPropertyMap = mapOf("requestId" to "req-1", "userId" to "42", "other" to "x") }

        val json = TestJson.boot.readTree(String(encoder.encode(event)))

        assertThat(json.propertyNames().asSequence().toSet())
            .containsExactlyInAnyOrder("@timestamp", "message", "logger_name", "thread_name", "level", "level_value", "requestId", "userId")
        assertThat(json["message"].asString()).isEqualTo("주문 7 실패")
        assertThat(json["level"].asString()).isEqualTo("WARN")
        assertThat(json["requestId"].asString()).isEqualTo("req-1")
    }
}
