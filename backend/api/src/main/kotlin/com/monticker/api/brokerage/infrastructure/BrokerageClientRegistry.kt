package com.monticker.api.brokerage.infrastructure

import com.monticker.api.brokerage.domain.BrokerageProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * ADR-026 — BrokerageService가 계좌의 provider에 맞는 BrokerageClient를 얻는 창구.
 * Mock/Real 스위칭은 이 레지스트리를 만드는 두 빈 팩토리로 옮겨졌다 — KisBrokerageClient/
 * MockBrokerageClient 자체의 @ConditionalOnProperty는 그대로 둔다.
 */
class BrokerageClientRegistry(private val byProvider: Map<BrokerageProvider, BrokerageClient>) {
    fun get(provider: BrokerageProvider): BrokerageClient =
        byProvider[provider] ?: throw IllegalStateException("지원하지 않는 증권사입니다: $provider")

    /**
     * ADR-060 — 이 증권사 계좌의 주문이 실제 돈을 움직이는가. Mock 모드에서는 KIS·Toss 계좌도 Mock 클라이언트로 가므로 계좌의
     * provider가 아니라 연결되는 클라이언트로 판정한다. 등록되지 않은 provider는 실제 돈으로 본다(fail-closed).
     */
    fun movesRealMoney(provider: BrokerageProvider): Boolean = byProvider[provider]?.movesRealMoney ?: true

    /** 실제 돈을 움직이지 않는 클라이언트가 하나라도 있는가 — 조건부 주문 평가기의 사전 필터(SpEL)가 쓴다. */
    fun anySimulated(): Boolean = byProvider.values.any { !it.movesRealMoney }
}

@Configuration
class BrokerageClientRegistryConfig {

    // 두 빈의 이름을 같게 둔다(동시에 하나만 존재) — ConditionalOrderEvaluator의 @EventListener 조건이 SpEL로
    // @brokerageClientRegistry를 참조한다(ADR-060).
    @Bean(BEAN_NAME)
    @ConditionalOnProperty("app.brokerage.mock.enabled", havingValue = "true", matchIfMissing = true)
    fun mockBrokerageClientRegistry(mock: MockBrokerageClient, health: BrokerCallHealthTracker): BrokerageClientRegistry =
        instrumented(BrokerageProvider.entries.associateWith { mock }, health)

    @Bean(BEAN_NAME)
    @ConditionalOnProperty("app.brokerage.mock.enabled", havingValue = "false")
    fun realBrokerageClientRegistry(kis: KisBrokerageClient, toss: TossBrokerageClient, health: BrokerCallHealthTracker): BrokerageClientRegistry =
        instrumented(
            mapOf(
                BrokerageProvider.KIS to kis,
                BrokerageProvider.TOSS to toss,
            ),
            health,
        )

    /** 계좌 상태의 API 지연·마지막 오류를 재는 데코레이터로 감싼다. 위임만 하고 동작은 바꾸지 않는다(InstrumentedBrokerageClient). */
    private fun instrumented(clients: Map<BrokerageProvider, BrokerageClient>, health: BrokerCallHealthTracker) =
        BrokerageClientRegistry(clients.mapValues { (provider, client) -> InstrumentedBrokerageClient(client, provider, health) })

    companion object {
        const val BEAN_NAME = "brokerageClientRegistry"
    }
}
