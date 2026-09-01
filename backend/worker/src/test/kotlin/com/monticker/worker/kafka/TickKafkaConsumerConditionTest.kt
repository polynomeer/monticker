package com.monticker.worker.kafka

import com.monticker.worker.detector.EventDetector
import com.monticker.worker.marketdata.CandleAggregator
import com.monticker.worker.marketdata.LatencyTracker
import com.monticker.worker.marketdata.RedisTickWriter
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADR-022: docker-compose msa 프로필의 worker-market/worker-event/worker-alert가
 * TickKafkaConsumer(market.ticks 컨슈머, CandleAggregator.onTick() 호출부)를 정확히
 * role=event(및 단일 프로세스 role=all)에서만 활성화하는지 검증한다.
 *
 * 수정 전 조건("role=='event' || tick.consumer=='legacy'")은 tick.consumer가 어디서도
 * 오버라이드되지 않아(기본값 legacy) role=market/alert에서도 항상 참이었다 — 이 테스트가
 * 없었다면 그 회귀를 아무것도 잡지 못했다.
 */
class TickKafkaConsumerConditionTest {

    @Configuration
    class Deps {
        @Bean fun redisTickWriter(): RedisTickWriter = mockk(relaxed = true)
        @Bean fun candleAggregator(): CandleAggregator = mockk(relaxed = true)
        @Bean fun eventDetector(): EventDetector = mockk(relaxed = true)
        @Bean fun latencyTracker(): LatencyTracker = mockk(relaxed = true)
    }

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(Deps::class.java, TickKafkaConsumer::class.java)

    @ParameterizedTest(name = "worker.role={0}, tick.consumer={1} -> active={2}")
    @CsvSource(
        // role=all (단일 프로세스, 기본값)
        "all,    legacy,      true",
        "all,    integration, false",
        // role=event: tick.consumer과 무관하게 항상 활성화
        "event,  legacy,      true",
        "event,  integration, true",
        // role=market/alert: msa 프로필 — market.ticks를 직접 소비할 이유가 없어 항상 비활성화
        "market, legacy,      false",
        "market, integration, false",
        "alert,  legacy,      false",
        "alert,  integration, false",
    )
    fun `activation matches designed role for each msa process`(
        role: String,
        tickConsumer: String,
        expectedActive: Boolean,
    ) {
        runner
            .withPropertyValues("worker.role=$role", "tick.consumer=$tickConsumer")
            .run { ctx: AssertableApplicationContext ->
                val hasBean = ctx.containsBean("tickKafkaConsumer")
                val msg = "role=$role tick.consumer=$tickConsumer expected active=$expectedActive but was $hasBean"
                if (expectedActive) {
                    assertTrue(hasBean, msg)
                } else {
                    assertFalse(hasBean, msg)
                }
            }
    }

    @Test
    fun `defaults (no properties set) behave as role=all`() {
        runner.run { ctx: AssertableApplicationContext ->
            assertTrue(ctx.containsBean("tickKafkaConsumer"))
        }
    }
}
