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
}

@Configuration
class BrokerageClientRegistryConfig {

    @Bean
    @ConditionalOnProperty("app.brokerage.mock.enabled", havingValue = "true", matchIfMissing = true)
    fun mockBrokerageClientRegistry(mock: MockBrokerageClient): BrokerageClientRegistry =
        BrokerageClientRegistry(BrokerageProvider.entries.associateWith { mock })

    @Bean
    @ConditionalOnProperty("app.brokerage.mock.enabled", havingValue = "false")
    fun realBrokerageClientRegistry(kis: KisBrokerageClient, toss: TossBrokerageClient): BrokerageClientRegistry =
        BrokerageClientRegistry(
            mapOf(
                BrokerageProvider.KIS to kis,
                BrokerageProvider.TOSS to toss,
            )
        )
}
