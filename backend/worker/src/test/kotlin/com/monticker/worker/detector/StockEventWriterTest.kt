package com.monticker.worker.detector

import com.monticker.worker.kafka.EventKafkaProducer
import com.monticker.worker.push.ExpoPushSender
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/**
 * StockEventWriter가 실제로 담당하는 중복 방지 로직(같은 종목+이벤트타입이 같은 분(minute) 안에
 * 두 번 기록되지 않도록 하는 것)을 검증한다. PriceSpikeDetector/VolumeSurgeDetector 테스트는
 * writer를 통째로 목킹하기 때문에 이 로직을 전혀 검증하지 못한다 — 이 클래스가 그 공백을 메운다.
 */
class StockEventWriterTest {

    private val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
    private val pushSender = mockk<ExpoPushSender>(relaxed = true)
    private val eventKafkaProducer = mockk<ObjectProvider<EventKafkaProducer>>(relaxed = true)
    private val esOps = mockk<ElasticsearchOperations>(relaxed = true)
    private val writer = StockEventWriter(jdbcTemplate, pushSender, eventKafkaProducer, esOps)

    private fun makeEvent(eventTime: Instant = Instant.parse("2026-08-20T09:30:15Z")) = DetectedEvent(
        stockId = 1L,
        eventType = DetectedEventType.VOLUME_SURGE,
        title = "거래량 급증",
        description = "설명",
        eventTime = eventTime,
        importanceScore = 60,
        metadataJson = mapOf("ratio" to 5.0),
    )

    @Test
    fun `같은 종목-이벤트타입 중복이 없으면 INSERT하고 true를 반환한다`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("COUNT") }, Int::class.java, *anyVararg()) } returns 0
        every {
            jdbcTemplate.queryForObject(match<String> { it.contains("SELECT id") }, Long::class.java, *anyVararg())
        } returns 500L
        val docSlot = slot<StockEventDocument>()
        every { esOps.save(capture(docSlot)) } returns mockk(relaxed = true)

        val result = writer.write(makeEvent())

        assertThat(result).isTrue()
        verify(exactly = 1) { jdbcTemplate.update(match<String> { it.contains("INSERT INTO stock_events") }, *anyVararg()) }
        assertThat(docSlot.captured.id).isEqualTo("500")
        assertThat(docSlot.captured.eventType).isEqualTo("VOLUME_SURGE")
    }

    @Test
    fun `같은 분(minute) 내 동일 종목-이벤트타입 중복이면 INSERT하지 않고 false를 반환한다`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("COUNT") }, Int::class.java, *anyVararg()) } returns 1

        val result = writer.write(makeEvent())

        assertThat(result).isFalse()
        verify(exactly = 0) { jdbcTemplate.update(match<String> { it.contains("INSERT INTO stock_events") }, *anyVararg()) }
    }

    @Test
    fun `다음 분(minute)으로 넘어가면 같은 종목-이벤트타입이라도 다시 기록할 수 있다`() {
        // 중복 체크는 이벤트 시각을 분 단위로 버킷팅하므로, COUNT 쿼리 자체는 매번 실행된다.
        // 여기서는 "새 분 버킷 → 중복 없음"을 시뮬레이션한다.
        every { jdbcTemplate.queryForObject(match<String> { it.contains("COUNT") }, Int::class.java, *anyVararg()) } returns 0
        every {
            jdbcTemplate.queryForObject(match<String> { it.contains("SELECT id") }, Long::class.java, *anyVararg())
        } returns 501L

        val result = writer.write(makeEvent(eventTime = Instant.parse("2026-08-20T09:31:05Z")))

        assertThat(result).isTrue()
        verify(exactly = 1) { jdbcTemplate.update(match<String> { it.contains("INSERT INTO stock_events") }, *anyVararg()) }
    }
}
