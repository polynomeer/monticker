package com.monticker.api.brokerage.infrastructure

import com.monticker.api.common.resilience.CircuitBreakerConfiguration
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 실브로커 클라이언트는 app.brokerage.mock.enabled=false — 즉 운영에서만 — 인스턴스화된다.
 * 그래서 로컬·CI에서는 한 번도 생성된 적이 없었고, @Value가 application.yml에 없는 키
 * (app.kis.base-url — 실제 키는 app.brokerage.kis.base-url)를 가리키는데도 아무도 몰랐다.
 * 운영 설정으로 부팅하면 PlaceholderResolutionException으로 기동이 실패했다 — 실제 사용자
 * 자금이 걸린 클라이언트 둘 다. CH-06 카오스 실험 1단계에서 발견 (resilience-plan §6.3).
 *
 * 이 테스트는 실제 application.yml을 읽어(ConfigDataApplicationContextInitializer) 운영 조건으로
 * 두 빈을 만들어 본다. 키 이름이 다시 어긋나면 여기서 잡힌다.
 */
class RealBrokerageClientsBootTest {

    @Configuration
    class Deps {
        @Bean fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

        companion object {
            // Spring Boot 앱은 이 빈을 자동 등록해 미해결 ${...}에 예외를 던진다(strict). 이게 없으면
            // ApplicationContextRunner는 Environment 폴백으로 placeholder를 "무시"하고 문자열 그대로 주입해
            // 버그를 통과시킨다 — 실제로 이 테스트가 처음엔 그렇게 오탐했다.
            @JvmStatic @Bean
            fun placeholderConfigurer() = org.springframework.context.support.PropertySourcesPlaceholderConfigurer()
        }
    }

    private val runner = ApplicationContextRunner()
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Deps::class.java, CircuitBreakerConfiguration::class.java,
            KisBrokerageClient::class.java, TossBrokerageClient::class.java)

    @Test
    fun `운영 조건(mock 비활성)으로 KIS·Toss 실브로커 클라이언트가 shipped 설정만으로 생성된다`() {
        runner.withPropertyValues("app.brokerage.mock.enabled=false").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(KisBrokerageClient::class.java)
            assertThat(ctx).hasSingleBean(TossBrokerageClient::class.java)
        }
    }

    @Test
    fun `mock 활성(로컬 기본값)에서는 실브로커 클라이언트가 만들어지지 않는다`() {
        runner.withPropertyValues("app.brokerage.mock.enabled=true").run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(KisBrokerageClient::class.java)
        }
    }
}
