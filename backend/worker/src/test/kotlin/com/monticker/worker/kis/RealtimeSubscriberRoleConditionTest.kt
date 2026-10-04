package com.monticker.worker.kis

import com.monticker.worker.toss.TossCoverageProvider
import com.monticker.worker.toss.TossExecutionTickHandler
import com.monticker.worker.toss.TossExecutionTickSubscriber
import com.monticker.worker.toss.TossTokenIssuer
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 실시간 구독기(KIS 체결·호가, Toss 체결)는 시세 생산 역할(market|all)에서만 생긴다. 예전엔 역할을 보지 않아, 역할 분리 배포에서
 * kis를 켜면 market·event·alert worker가 같은 앱키로 각자 웹소켓을 열어 등록 한도를 나눠 쓰고 틱을 중복 생산했다.
 * (키가 없으면 구독기는 생겨도 연결하지 않는다 — 여기 목은 미설정 상태다.)
 */
class RealtimeSubscriberRoleConditionTest {

    @Configuration
    class Deps {
        @Bean fun kisWebSocketClient(): KisWebSocketClient = mockk(relaxed = true)
        @Bean fun kisCoverageProvider(): KisCoverageProvider = mockk(relaxed = true)
        @Bean fun tossTokenIssuer(): TossTokenIssuer = mockk(relaxed = true)
        @Bean fun tossExecutionTickHandler(): TossExecutionTickHandler = mockk(relaxed = true)
        @Bean fun tossCoverageProvider(): TossCoverageProvider = mockk(relaxed = true)
        @Bean fun jdbcTemplate(): JdbcTemplate = mockk(relaxed = true)
    }

    private val runner = ApplicationContextRunner().withUserConfiguration(
        Deps::class.java, KisExecutionTickSubscriber::class.java, KisOrderBookSubscriber::class.java, TossExecutionTickSubscriber::class.java,
    )

    @ParameterizedTest(name = "worker.role={0} -> subscribers={1}")
    @CsvSource("all, true", "market, true", "event, false", "alert, false")
    fun `실시간 구독기는 시세 생산 역할에서만 생긴다`(role: String, expected: Boolean) {
        runner.withPropertyValues("worker.role=$role", "ingestion.source=kis,toss").run { ctx ->
            assertThat(ctx.getBeansOfType(KisExecutionTickSubscriber::class.java).isNotEmpty()).isEqualTo(expected)
            assertThat(ctx.getBeansOfType(KisOrderBookSubscriber::class.java).isNotEmpty()).isEqualTo(expected)
            assertThat(ctx.getBeansOfType(TossExecutionTickSubscriber::class.java).isNotEmpty()).isEqualTo(expected)
        }
    }
}
