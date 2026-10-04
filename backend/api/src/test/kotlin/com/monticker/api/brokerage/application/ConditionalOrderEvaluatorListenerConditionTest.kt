package com.monticker.api.brokerage.application

import com.monticker.api.marketdata.domain.MarketTickReceivedEvent
import com.monticker.api.marketdata.domain.PriceSource
import com.monticker.api.marketdata.domain.PriceTick
import com.monticker.api.marketdata.domain.TickProvenance
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.Instant

/**
 * ADR-055 — `@EventListener(condition = "#event.provenance.source.real")`는 SpEL 문자열이라 컴파일러가
 * 검증하지 못한다. 오타가 나면 모든 틱이 걸러지거나(실주문 전면 정지) 예외가 난다. 실제 Spring 이벤트
 * 디스패치로 확인한다(@EnableAsync 없이 띄워 동기로 실행된다).
 */
class ConditionalOrderEvaluatorListenerConditionTest {

    private val jdbc = mockk<JdbcTemplate>()
    private val registry = SimpleMeterRegistry()

    private fun publish(source: PriceSource) {
        AnnotationConfigApplicationContext().use { ctx ->
            ctx.beanFactory.registerSingleton("jdbc", jdbc)
            ctx.beanFactory.registerSingleton("brokerageService", mockk<BrokerageService>())
            ctx.beanFactory.registerSingleton("meterRegistry", registry)
            ctx.beanFactory.registerSingleton("tradingHaltService", mockk<TradingHaltService> { every { findActive(any(), any()) } returns null })
            ctx.register(ConditionalOrderEvaluator::class.java)
            ctx.refresh()
            ctx.publishEvent(
                MarketTickReceivedEvent(
                    PriceTick(1L, "005930", BigDecimal("69000"), 10L, Instant.now()),
                    TickProvenance(source, "OPEN", Instant.now()),
                ),
            )
        }
    }

    @Test
    fun `합성 틱은 리스너까지 오지 않는다`() {
        publish(PriceSource.MOCK)
        verify(exactly = 0) { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
        // 리스너 안의 게이트까지 왔다면 이 카운터가 올라간다 — 0이어야 디스패치 전에 걸러진 것이다
        assertThat(registry.find("conditional_order_tick_ignored_total").counters().sumOf { it.count() }).isZero()
    }

    @Test
    fun `실시세 틱은 리스너가 받아 활성 조건부 주문을 조회한다`() {
        every { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } returns emptyList()
        publish(PriceSource.KIS)
        verify(exactly = 1) { jdbc.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
    }
}
